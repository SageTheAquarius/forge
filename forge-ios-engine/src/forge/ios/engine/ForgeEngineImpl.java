package forge.ios.engine;

import org.robovm.apple.foundation.NSObject;
import org.robovm.objc.annotation.CustomClass;

import forge.gui.GuiBase;
import forge.sim.ForgeServer;
import forge.sim.GuiHeadless;

/**
 * Framework facade. MobiVM's framework support calls {@link #instantiate()}
 * once (rvmInstantiateFramework in ForgeEngine.h) and hands the host app this
 * object; {@link #start} boots the same ForgeServer the fly.io box runs, on a
 * background thread, listening on 127.0.0.1.
 *
 * Directory contract (all absolute, trailing slash optional):
 *   assetsDir  read-only, contains res/ (cardsfolder.zip, editions, ...)
 *   userDir    writable, Forge's profile (decks the relay writes, prefs)
 *   cacheDir   writable, Forge's cache
 * These map onto upstream's own iOS convention (forge.ios.userDir /
 * forge.ios.cacheDir, honoured by ForgeProfileProperties when the GUI reports
 * it is not running on a desktop).
 */
@CustomClass("ForgeEngineImpl")
public class ForgeEngineImpl extends NSObject implements ForgeEngineApi {
    private static final ForgeEngineImpl INSTANCE = new ForgeEngineImpl();

    private volatile Thread server;
    private volatile boolean ready;
    private volatile int port;
    private volatile String lastError = "";

    public static NSObject instantiate() {
        return INSTANCE;
    }

    @Override
    public synchronized void start(final int port0, final String assetsDir, final String userDir,
            final String cacheDir) {
        if (server != null) {
            return;
        }
        final String assets = slash(assetsDir);
        System.setProperty("forge.ios.userDir", slash(userDir));
        System.setProperty("forge.ios.cacheDir", slash(cacheDir));
        System.setProperty("forge.assets.dir", assets);
        System.setProperty("forge.ios", "true");
        // Upstream's iOS boot: the static/replacement-ability memo is proven
        // stale-free and cuts allocations on a phone CPU.
        if (System.getProperty("forge.staticMemo") == null) {
            System.setProperty("forge.staticMemo", "on");
        }
        if (System.getProperty("user.timezone") == null || System.getProperty("user.timezone").isEmpty()) {
            System.setProperty("user.timezone", "UTC");
        }
        System.setProperty("bridge.port", Integer.toString(port0));
        // The two AI switches the production JVM is started with
        // (deploy/entrypoint.sh: -Dai.trimzones -Dai.sacache): presence is
        // what Forge tests, so an empty value is the same as the flag.
        if (System.getProperty("ai.trimzones") == null) {
            System.setProperty("ai.trimzones", "");
        }
        if (System.getProperty("ai.sacache") == null) {
            System.setProperty("ai.sacache", "");
        }
        GuiBase.setInterface(new GuiHeadless(assets, false));
        port = port0;
        ready = false;
        lastError = "";
        // 8 MB stack: the rules engine recurses deeply (replacement effects,
        // AI simulation) and a phone thread's default is far smaller than a
        // desktop JVM's.
        server = new Thread(null, () -> {
            try {
                ForgeServer.serve(port0, () -> ready = true);
            } catch (Throwable t) {
                lastError = t.toString();
                System.err.println("[ForgeEngine] server thread died: " + t);
                t.printStackTrace();
            }
        }, "Game loop (bridge)", 8L * 1024 * 1024);
        server.setDaemon(true);
        server.start();
    }

    @Override
    public boolean isReady() {
        return ready;
    }

    @Override
    public int port() {
        return port;
    }

    @Override
    public String lastError() {
        return lastError;
    }

    private static String slash(final String dir) {
        if (dir == null || dir.isEmpty()) {
            return "";
        }
        return dir.endsWith("/") ? dir : dir + "/";
    }
}
