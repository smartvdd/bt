import bt.Bt;
import bt.data.Storage;
import bt.data.file.FileSystemStorage;
import bt.dht.DHTConfig;
import bt.dht.DHTModule;
import bt.runtime.BtClient;
import bt.runtime.Config;
import com.google.inject.Module;

import java.nio.file.Path;
import java.nio.file.Paths;

public class MagnetSample {

    public static void main(String[] args) {

        // enable multithreaded verification of torrent data
        Config config = new Config() {
            @Override
            public int getNumOfHashingThreads() {
                return Runtime.getRuntime().availableProcessors() * 2;
            }
        };

// enable bootstrapping from public routers
        Module dhtModule = new DHTModule(new DHTConfig() {
            @Override
            public boolean shouldUseRouterBootstrap() {
                return true;
            }
        });

// get download directory
        Path targetDirectory = Paths.get("/Users","vdg", "Downloads");

// create file system based backend for torrent data
        Storage storage = new FileSystemStorage(targetDirectory);

// create client with a private runtime
        BtClient client = Bt.client()
                .config(config)
                .storage(storage)
                .magnet("magnet:?xt=urn:btih:9dab6d80a93725614fbc9853f1f420de98943b76&dn=IMG_20191024_142106.jpg")
                .autoLoadModules()
                .module(dhtModule)
                .stopWhenDownloaded()
                .build();

// launch
        client.startAsync().join();


    }

}
