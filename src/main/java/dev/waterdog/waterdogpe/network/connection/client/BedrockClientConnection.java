/*
 * Copyright 2022 WaterdogTEAM
 * Licensed under the GNU General Public License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * https://www.gnu.org/licenses/old-licenses/gpl-2.0.html
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package dev.waterdog.waterdogpe.network.connection.client;

import dev.waterdog.waterdogpe.network.connection.codec.BedrockBatchWrapper;
import dev.waterdog.waterdogpe.network.connection.codec.batch.FrameIdCodec;
import dev.waterdog.waterdogpe.network.connection.codec.compression.CompressionAlgorithm;
import dev.waterdog.waterdogpe.network.connection.codec.compression.NoopCompressionCodec;
import dev.waterdog.waterdogpe.network.connection.codec.encryption.BedrockEncryptionDecoder;
import dev.waterdog.waterdogpe.network.connection.codec.encryption.BedrockEncryptionEncoder;
import dev.waterdog.waterdogpe.network.connection.codec.packet.BedrockPacketCodec;
import dev.waterdog.waterdogpe.network.protocol.ProtocolVersion;
import dev.waterdog.waterdogpe.network.protocol.handler.ProxyBatchBridge;
import dev.waterdog.waterdogpe.network.serverinfo.ServerInfo;
import dev.waterdog.waterdogpe.player.ProxiedPlayer;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import lombok.extern.log4j.Log4j2;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.cloudburstmc.netty.channel.raknet.RakChannel;
import org.cloudburstmc.netty.channel.raknet.RakDisconnectReason;
import org.cloudburstmc.netty.handler.codec.raknet.common.RakSessionCodec;
import org.cloudburstmc.protocol.bedrock.codec.BedrockCodec;
import org.cloudburstmc.protocol.bedrock.codec.BedrockCodecHelper;
import org.cloudburstmc.protocol.bedrock.codec.v428.Bedrock_v428;
import org.cloudburstmc.protocol.bedrock.netty.BedrockPacketWrapper;
import org.cloudburstmc.protocol.bedrock.netty.codec.compression.CompressionCodec;
import org.cloudburstmc.protocol.bedrock.packet.BedrockPacket;
import org.cloudburstmc.protocol.bedrock.packet.BedrockPacketHandler;
import org.cloudburstmc.protocol.bedrock.util.EncryptionUtils;

import javax.crypto.SecretKey;
import java.net.SocketAddress;
import java.util.List;
import java.util.Objects;

import static dev.waterdog.waterdogpe.network.connection.codec.initializer.ProxiedSessionInitializer.getCompressionCodec;

@Log4j2
public class BedrockClientConnection extends SimpleChannelInboundHandler<BedrockBatchWrapper> implements ClientConnection {
    private final ProxiedPlayer player;
    private final ServerInfo serverInfo;
    private final Channel channel;

    private final List<Runnable> disconnectListeners = new ObjectArrayList<>();

    private BedrockPacketHandler packetHandler;
    private CompressionAlgorithm compression;

    public BedrockClientConnection(ProxiedPlayer player, ServerInfo serverInfo, Channel channel) {
        this.player = player;
        this.serverInfo = serverInfo;
        this.channel = channel;
        if (player.getProtocol().isBefore(ProtocolVersion.MINECRAFT_PE_1_19_30)) {
            this.compression = CompressionAlgorithm.ZLIB;
        }
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) throws Exception {
        this.disconnectListeners.forEach(Runnable::run);
        super.channelInactive(ctx);
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, BedrockBatchWrapper batch) {
        if (this.packetHandler instanceof ProxyBatchBridge bridge) {
            bridge.onBedrockBatch(this, batch);
        } else if (this.packetHandler != null) {
            for (BedrockPacketWrapper packet : batch.getPackets()) {
                this.packetHandler.handlePacket(packet.getPacket());
            }
        } else {
            log.warn("Received unhandled packets for " + this.getSocketAddress());
        }
    }

    @Override
    public void sendPacket(BedrockBatchWrapper wrapper) {
        if (!Objects.equals(wrapper.getAlgorithm(), this.compression)) {
            wrapper.setCompressed(null);
        }
        this.channel.writeAndFlush(wrapper);
    }

    @Override
    public void sendPacket(BedrockPacket packet) {
        this.channel.writeAndFlush(BedrockBatchWrapper.create(0, packet));
    }

    @Override
    public void setCompression(CompressionAlgorithm compression) {
        ChannelHandler handler = this.channel.pipeline().get(CompressionCodec.NAME);
        if (handler instanceof NoopCompressionCodec) {
            this.channel.pipeline().remove(CompressionCodec.NAME);
        } else if (handler != null) {
            throw new IllegalArgumentException("Compression is already set");
        }
        this.channel.pipeline()
                .addAfter(FrameIdCodec.NAME, CompressionCodec.NAME, getCompressionCodec(compression, this.player.getProtocol().getRaknetVersion(), false));
        this.compression = compression;
    }

    @Override
    public void enableEncryption(@NonNull SecretKey secretKey) {
        if (!secretKey.getAlgorithm().equals("AES")) {
            throw new IllegalArgumentException("Invalid key algorithm");
        }
        // Check if the codecs exist in the pipeline
        if (this.channel.pipeline().get(BedrockEncryptionEncoder.class) != null ||
                this.channel.pipeline().get(BedrockEncryptionDecoder.class) != null) {
            throw new IllegalStateException("Encryption is already enabled");
        }

        int protocolVersion = this.getCodec().getProtocolVersion();
        boolean useCtr = protocolVersion >= Bedrock_v428.CODEC.getProtocolVersion();

        this.channel.pipeline().addAfter(FrameIdCodec.NAME, BedrockEncryptionEncoder.NAME,
                new BedrockEncryptionEncoder(secretKey, EncryptionUtils.createCipher(useCtr, true, secretKey)));
        this.channel.pipeline().addAfter(FrameIdCodec.NAME, BedrockEncryptionDecoder.NAME,
                new BedrockEncryptionDecoder(secretKey, EncryptionUtils.createCipher(useCtr, false, secretKey)));

        log.info("Encryption enabled for {}", this.getSocketAddress());
    }

    @Override
    public void setCodecHelper(BedrockCodec codec, BedrockCodecHelper helper) {
        Objects.requireNonNull(codec, "codec");
        Objects.requireNonNull(helper, "helper");
        this.channel.pipeline().get(BedrockPacketCodec.class).setCodecHelper(codec, helper);
    }

    private BedrockCodec getCodec() {
        return this.channel.pipeline().get(BedrockPacketCodec.class).getCodec();
    }

    @Override
    public void disconnect() {
        if (this.channel instanceof RakChannel rakChannel) {
            RakSessionCodec codec = rakChannel.rakPipeline().get(RakSessionCodec.class);
            codec.disconnect(RakDisconnectReason.DISCONNECTED);
        } else {
            this.channel.close();
        }
    }

    @Override
    public ProxiedPlayer getPlayer() {
        return this.player;
    }

    @Override
    public ServerInfo getServerInfo() {
        return this.serverInfo;
    }

    @Override
    public boolean isConnected() {
        return this.channel.isOpen();
    }

    @Override
    public SocketAddress getSocketAddress() {
        return this.channel.remoteAddress();
    }

    @Override
    public BedrockPacketHandler getPacketHandler() {
        return this.packetHandler;
    }

    @Override
    public void setPacketHandler(BedrockPacketHandler packetHandler) {
        this.packetHandler = packetHandler;
    }

    @Override
    public void addDisconnectListener(Runnable listener) {
        this.disconnectListeners.add(listener);
    }
}