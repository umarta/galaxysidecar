#import "VirtualDisplay.h"

@protocol GSDisplayDescriptor
- (void)setName:(NSString *)name;
- (void)setQueue:(dispatch_queue_t)queue;
- (void)setMaxPixelsWide:(unsigned int)width;
- (void)setMaxPixelsHigh:(unsigned int)height;
- (void)setSizeInMillimeters:(CGSize)size;
- (void)setVendorID:(unsigned int)vendor;
- (void)setProductID:(unsigned int)product;
- (void)setSerialNum:(unsigned int)serial;
@end

@protocol GSDisplayMode
- (instancetype)initWithWidth:(unsigned int)width height:(unsigned int)height refreshRate:(double)refreshRate;
@end

@protocol GSDisplaySettings
- (void)setHiDPI:(unsigned int)enabled;
- (void)setModes:(NSArray *)modes;
@end

@protocol GSDisplay
- (instancetype)initWithDescriptor:(NSObject *)descriptor;
- (BOOL)applySettings:(NSObject *)settings;
- (CGDirectDisplayID)displayID;
@end

NSObject *GSCreateVirtualDisplay(uint32_t width, uint32_t height, CGDirectDisplayID *displayID) {
    Class descriptorClass = NSClassFromString(@"CGVirtualDisplayDescriptor");
    Class displayClass = NSClassFromString(@"CGVirtualDisplay");
    Class settingsClass = NSClassFromString(@"CGVirtualDisplaySettings");
    Class modeClass = NSClassFromString(@"CGVirtualDisplayMode");
    if (![descriptorClass instancesRespondToSelector:@selector(setQueue:)] ||
        ![displayClass instancesRespondToSelector:@selector(initWithDescriptor:)] ||
        ![displayClass instancesRespondToSelector:@selector(applySettings:)] ||
        ![displayClass instancesRespondToSelector:@selector(displayID)] ||
        ![settingsClass instancesRespondToSelector:@selector(setModes:)] ||
        ![modeClass instancesRespondToSelector:@selector(initWithWidth:height:refreshRate:)]) {
        return nil;
    }

    // This adapter uses private APIs. Fail closed if macOS removes the selectors or rejects the display.
    @try {
        NSObject<GSDisplayDescriptor> *descriptor = [[descriptorClass alloc] init];
        [descriptor setName:@"Galaxy Tab S10+"];
        [descriptor setQueue:dispatch_get_main_queue()];
        [descriptor setMaxPixelsWide:width];
        [descriptor setMaxPixelsHigh:height];
        [descriptor setSizeInMillimeters:CGSizeMake(266, 166)];
        [descriptor setVendorID:0x4753];
        [descriptor setProductID:0x0010];
        [descriptor setSerialNum:1];

        NSObject<GSDisplay> *display = [[displayClass alloc] initWithDescriptor:descriptor];
        NSObject<GSDisplayMode> *mode = [[modeClass alloc] initWithWidth:width / 2 height:height / 2 refreshRate:60];
        NSObject<GSDisplaySettings> *settings = [[settingsClass alloc] init];
        if (!display || !mode || !settings) return nil;
        [settings setHiDPI:1];
        [settings setModes:@[mode]];
        if (![display applySettings:settings]) return nil;
        *displayID = [display displayID];
        return *displayID ? display : nil;
    } @catch (__unused NSException *exception) {
        return nil;
    }
}
