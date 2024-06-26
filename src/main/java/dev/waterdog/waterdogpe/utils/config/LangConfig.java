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

package dev.waterdog.waterdogpe.utils.config;

import dev.waterdog.waterdogpe.logger.MainLogger;
import dev.waterdog.waterdogpe.utils.FileUtils;
import dev.waterdog.waterdogpe.utils.types.TextContainer;
import dev.waterdog.waterdogpe.utils.types.TranslationContainer;
import it.unimi.dsi.fastutil.objects.Object2ObjectMap;
import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;
import lombok.Getter;

import java.io.File;
import java.io.IOException;

public class LangConfig {

    @Getter
    protected final Object2ObjectMap<String, String> transactionMap = new Object2ObjectOpenHashMap<>();
    private final File file;

    public LangConfig(File file) {
        this.file = file;
        this.load();
    }

    public void load() {
        try {
            String content = FileUtils.readFile(this.file);
            for (String line : content.split("\n")) {
                line = line.trim();
                if (line.isEmpty() || line.charAt(0) == '#') {
                    continue;
                }
                String[] t = line.split("=");
                if (t.length < 2) {
                    continue;
                }
                String key = t[0];
                StringBuilder value = new StringBuilder();
                for (int i = 1; i < t.length - 1; i++) {
                    value.append(t[i]).append("=");
                }
                value.append(t[t.length - 1]);
                if (value.toString().isEmpty()) {
                    continue;
                }
                this.transactionMap.put(key, value.toString());
            }

        } catch (IOException e) {
            MainLogger.getLogger().error("Unable to load Config " + this.file);
        }
    }

    public String translateString(String key, String... args) {
        String string = this.getTransaction(key);
        if (string == null) return key;

        if (args != null) {
            for (int i = 0; i < args.length; i++) {
                string = string.replace("{%" + i + "}", String.valueOf(args[i]));
            }
        }
        return string;
    }

    public String translateContainer(TextContainer textContainer) {
        if (!(textContainer instanceof TranslationContainer)) {
            return textContainer.getMessage();
        }
        return this.translateString(textContainer.getMessage(), ((TranslationContainer) textContainer).getParams());
    }

    public String getTransaction(String key) {
        return this.transactionMap.get(key);
    }
}
