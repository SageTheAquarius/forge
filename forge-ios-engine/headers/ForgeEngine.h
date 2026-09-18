// ForgeEngine.framework — the Forge rules engine + AI + EconomyDraft bridge
// server, AOT-compiled from Java by MobiVM. Umbrella header for the host app.
//
// Usage (Objective-C / Objective-C++):
//
//     #import <ForgeEngine/ForgeEngine.h>
//     ForgeEngine *engine = ForgeEngineInstance();          // boots the VM once
//     [engine startWithPort:8781
//                 assetsDir:@"/path/to/app/bundle/forge/"   // has res/ under it
//                   userDir:@"/path/to/Documents/forge/"    // writable
//                  cacheDir:@"/path/to/Documents/forge/cache/"];
//     while (![engine isReady]) { /* poll; card DB load takes a few seconds */ }
//     // then connect to 127.0.0.1:8781 with the bridge protocol
//
// The Java entry runs on its own thread; start() returns at once. Calling it
// twice is a no-op. lastError is non-empty if the server thread died.

#import <Foundation/Foundation.h>

@protocol ForgeEngineApi <NSObject>
- (void)startWithPort:(int)port
            assetsDir:(NSString *)assetsDir
              userDir:(NSString *)userDir
             cacheDir:(NSString *)cacheDir;
- (BOOL)isReady;
- (int)port;
- (NSString *)lastError;
@end

typedef NSObject<ForgeEngineApi> ForgeEngine;

#ifdef __cplusplus
extern "C" {
#endif
// MobiVM framework support: creates the VM on first call and returns the
// facade singleton (forge.ios.engine.ForgeEngineImpl.instantiate()).
NSObject *rvmInstantiateFramework(const char *className);
#ifdef __cplusplus
}
#endif

static inline ForgeEngine *ForgeEngineInstance(void) {
    return (ForgeEngine *)rvmInstantiateFramework("forge.ios.engine.ForgeEngineImpl");
}
