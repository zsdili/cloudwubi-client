/*
 * main.m - CloudWubi macOS 输入法程序入口
 *
 * 启动 IMKServer，连接 InputMethodKit 框架。
 * 连接名与 Info.plist 的 InputMethodConnectionName 保持一致。
 */

#import <Cocoa/Cocoa.h>
#import <InputMethodKit/InputMethodKit.h>
#import "CloudWubiController.h"

/* 连接名（必须与 Info.plist 一致） */
static NSString *const kConnectionName = @"com.cloudwubi.inputmethod.Connection";

int main(int argc, const char *argv[])
{
    @autoreleasepool {
        /* 确保输入法进程只加载一次 */
        if (getenv("CLOUDWUBI_IMK_SERVER")) return 0;

        IMKServer *server = [[IMKServer alloc]
            initWithName:kConnectionName
         controllerClass:[CloudWubiController class]
           delegateClass:[CloudWubiController class]];

        if (server == nil) {
            NSLog(@"CloudWubi: IMKServer 初始化失败");
            return 1;
        }

        NSLog(@"CloudWubi 输入法已启动（%@）", kConnectionName);
        /* 启动事件循环 */
        [[NSApplication sharedApplication] run];
    }
    return 0;
}
