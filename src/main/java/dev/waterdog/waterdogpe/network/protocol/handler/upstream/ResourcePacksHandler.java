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

package dev.waterdog.waterdogpe.network.protocol.handler.upstream;

import org.cloudburstmc.protocol.bedrock.packet.*;
import dev.waterdog.waterdogpe.event.defaults.PlayerResourcePackApplyEvent;
import dev.waterdog.waterdogpe.packs.PackManager;
import dev.waterdog.waterdogpe.player.ProxiedPlayer;
import org.cloudburstmc.protocol.common.PacketSignal;

import java.util.LinkedList;
import java.util.Queue;

/**
 * Upstream handler handling proxy manager resource packs.
 */
public class ResourcePacksHandler extends AbstractUpstreamHandler {

    private final Queue<ResourcePackDataInfoPacket> pendingPacks = new LinkedList<>();
    private ResourcePackDataInfoPacket sendingPack;

    public ResourcePacksHandler(ProxiedPlayer player) {
        super(player);
    }

    @Override
    public PacketSignal handle(ResourcePackClientResponsePacket packet) {
        PackManager packManager = this.player.getProxy().getPackManager();

        switch (packet.getStatus()) {
            case REFUSED -> this.player.disconnect("disconnectionScreen.noReason");
            case SEND_PACKS -> {
                for (String packIdVer : packet.getPackIds()) {
                    ResourcePackDataInfoPacket response = packManager.packInfoFromIdVer(packIdVer);
                    if (response == null) {
                        this.player.disconnect("disconnectionScreen.resourcePack");
                        break;
                    }
                    this.pendingPacks.offer(response);
                }
                this.sendNextPacket();
            }
            case HAVE_ALL_PACKS -> {
                PlayerResourcePackApplyEvent event = new PlayerResourcePackApplyEvent(this.player, packManager.getStackPacket());
                this.player.getProxy().getEventManager().callEvent(event);
                this.player.getConnection().sendPacket(event.getStackPacket());
            }
            case COMPLETED -> {
                if (!this.player.hasUpstreamBridge()) {
                    this.player.initialConnect(); // First connection
                }
            }
        }

        return this.cancel();
    }

    @Override
    public PacketSignal handle(ResourcePackChunkRequestPacket packet) {
        PackManager packManager = this.player.getProxy().getPackManager();
        ResourcePackChunkDataPacket response = packManager.packChunkDataPacket(packet.getPackId() + "_" + packet.getPackVersion(), packet);
        if (response == null) {
            this.player.disconnect("Unknown resource pack!");
        } else {
            this.player.sendPacket(response);
            if (this.sendingPack != null && (packet.getChunkIndex() + 1) >= this.sendingPack.getChunkCount()) {
                this.sendNextPacket();
            }
        }
        return this.cancel();
    }

    private void sendNextPacket() {
        ResourcePackDataInfoPacket infoPacket = this.pendingPacks.poll();
        if (infoPacket != null && this.player.isConnected()) {
            this.sendingPack = infoPacket;
            this.player.sendPacket(infoPacket);
        }
    }
}
