package forge.ios.engine;

import org.robovm.objc.ObjCProtocol;
import org.robovm.objc.annotation.Method;

/**
 * The Objective-C protocol the host app sees (headers/ForgeEngine.h mirrors
 * it). Selectors are fixed here so the header never drifts from the Java.
 */
public interface ForgeEngineApi extends ObjCProtocol {
    @Method(selector = "startWithPort:assetsDir:userDir:cacheDir:")
    void start(int port, String assetsDir, String userDir, String cacheDir);

    @Method(selector = "isReady")
    boolean isReady();

    @Method(selector = "port")
    int port();

    @Method(selector = "lastError")
    String lastError();
}
