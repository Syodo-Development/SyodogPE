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

package dev.waterdog.waterdogpe.event.defaults;

import dev.waterdog.waterdogpe.event.CancellableEvent;
import dev.waterdog.waterdogpe.network.serverinfo.ServerInfo;
import dev.waterdog.waterdogpe.player.ProxiedPlayer;
import lombok.Getter;
import lombok.Setter;

/**
 * This event will be called before an initial connection to the downstream target is made.
 * This gives us the option to completely cancel a transfer, for example to restrict access to a server.
 * Cancelling this event will simply interrupt and cancel the transfer procedure and the player will stay on the old downstream server.
 */
@Getter
@Setter
public class ServerTransferRequestEvent extends PlayerEvent implements CancellableEvent {

    private ServerInfo targetServer;

    public ServerTransferRequestEvent(ProxiedPlayer player, ServerInfo targetServer) {
        super(player);

        this.targetServer = targetServer;
    }
}
