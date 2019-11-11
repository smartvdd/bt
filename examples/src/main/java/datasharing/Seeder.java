/*
 * Copyright (c) 2016—2018 Andrei Tomashpolskiy and individual contributors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package datasharing;

import bt.Bt;
import bt.data.Storage;
import bt.data.file.FileSystemStorage;
import bt.runtime.BtClient;
import bt.runtime.Config;

import java.nio.file.Paths;

public class Seeder {

    public static final int PORT = 6891;

    public static void main(String[] args) throws Exception {
        Config config = new Config() {
            @Override
            public int getNumOfHashingThreads() {
                return Runtime.getRuntime().availableProcessors() * 2;
            }

            @Override
            public int getAcceptorPort() {
                return PORT;
            }
        };

        Storage storage = new FileSystemStorage(Paths.get("/Users", "vdg", "Downloads", "IMG_1141.JPG"));
        BtClient client = Bt.client()
                .config(config)
                .storage(storage)
                .magnet("magnet:?xt=urn:btih:39caae1617420b970853ffedef9a67f21ee7ca19&dn=IMG_1141.JPG")
                .autoLoadModules()
                .build();

        System.err.println("Starting seeder...");
        client.startAsync().join();
    }
}
