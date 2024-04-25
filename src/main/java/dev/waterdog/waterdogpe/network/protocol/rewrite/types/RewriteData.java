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

package dev.waterdog.waterdogpe.network.protocol.rewrite.types;

import dev.waterdog.waterdogpe.ProxyServer;
import dev.waterdog.waterdogpe.network.protocol.handler.TransferCallback;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.Setter;
import org.cloudburstmc.math.vector.Vector2f;
import org.cloudburstmc.math.vector.Vector3f;
import org.cloudburstmc.protocol.bedrock.codec.BedrockCodecHelper;
import org.cloudburstmc.protocol.bedrock.data.BlockPropertyData;
import org.cloudburstmc.protocol.bedrock.data.GameRuleData;

import java.util.List;

/**
 * Rewrite data of a present player.
 * Holds both the client-known entityId and the downstream-known clientId.
 * Important when interacting when packets, as different packet targets might want different entityIds.
 */
@Getter
@Setter
public class RewriteData {

    /**
     * The original entityId known to the client
     */
    private long entityId;
    /**
     * The downstream-known entityId
     */
    private long originalEntityId;

    private BlockPalette blockPalette;
    private BlockPaletteRewrite blockPaletteRewrite;
    private List<BlockPropertyData> blockProperties;

    /**
     * A list of GameRules currently known to the client.
     */
    private List<GameRuleData<?>> gameRules;
    /**
     * The dimensionId the player is currently in
     */
    private int dimension = 0;
    private TransferCallback transferCallback;

    private Vector3f spawnPosition;
    private Vector2f rotation;
    /**
     * Server known value of immobile flag
     * Actually applied value may be different during server transfer
     */
    @Getter(AccessLevel.NONE)
    private boolean immobileFlag;

    /**
     * The name that is shown up in the player list (or pause menu)
     */
    private String proxyName;


    private BedrockCodecHelper codecHelper;

    public RewriteData() {
        this.proxyName = ProxyServer.getInstance().getConfiguration().getName();
    }

    public boolean hasImmobileFlag() {
        return this.immobileFlag;
    }
}
