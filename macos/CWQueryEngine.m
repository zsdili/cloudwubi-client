/*
 * CWQueryEngine.m - CloudWubi macOS 输入法核心查询引擎实现
 *
 * 复用 C 内核模块：
 *   wubi_engine.h  - 编码合法性校验 + 本地兜底简码
 *   http_client.h  - POST 到云端网关
 *   json_parser.h  - 解析 candidates / phrases
 */

#import "CWQueryEngine.h"

/* 引入 C 内核模块 */
#include "../src/wubi_engine.h"
#include "../src/http_client.h"
#include "../src/json_parser.h"

@implementation CWCandidate
@end

@implementation CWQueryResult
@end

@interface CWQueryEngine ()
@property (nonatomic, strong) NSMutableString *codeBuffer;
@end

@implementation CWQueryEngine

+ (instancetype)sharedEngine
{
    static CWQueryEngine *engine = nil;
    static dispatch_once_t once;
    dispatch_once(&once, ^{
        engine = [[CWQueryEngine alloc] init];
    });
    return engine;
}

- (instancetype)init
{
    self = [super init];
    if (self) {
        _codeBuffer = [NSMutableString stringWithCapacity:4];
        /* 默认本地演示网关（部署后由 Info.plist / 偏好配置覆盖） */
        _gatewayHost = @"127.0.0.1";
        _gatewayPort = 8000;
        _gatewayPath = @"/wubi/query";
    }
    return self;
}

- (NSString *)composingCode
{
    return [_codeBuffer copy];
}

- (BOOL)appendKey:(char)c
{
    /* 五笔编码仅接受 a~y 小写 */
    if (c < 'a' || c > 'y') return NO;
    if (_codeBuffer.length >= 4) return NO;  /* 最多 4 码 */

    unichar ch = (unichar)c;
    [_codeBuffer appendString:[NSString stringWithCharacters:&ch length:1]];
    return YES;
}

- (void)clearComposing
{
    [_codeBuffer setString:@""];
}

- (CWQueryResult *)queryCandidates
{
    CWQueryResult *result = [[CWQueryResult alloc] init];
    result.code = [_codeBuffer copy];
    result.candidates = [NSMutableArray array];

    if (_codeBuffer.length == 0) return result;

    const char *code_cstr = [_codeBuffer UTF8String];
    size_t code_len = strlen(code_cstr);

    /* 1) 本地兜底简码（离线也能打一级简码） */
    uint32_t fallback = 0;
    if (wubi_fallback_query(code_cstr, &fallback) && fallback != 0) {
        CWCandidate *cand = [[CWCandidate alloc] init];
        cand.phrase = [self unicodeChar:fallback];
        cand.isPhrase = NO;
        [(NSMutableArray *)result.candidates addObject:cand];
    }

    /* 2) 联网查询云端（构词 + AI 排序） */
    char body[160];
    snprintf(body, sizeof(body), "{\"code\":\"%s\",\"phrase\":true}", code_cstr);

    char response[8192];
    if (http_post([_gatewayHost UTF8String], _gatewayPort, [_gatewayPath UTF8String],
                  body, response, sizeof(response)) == 0) {
        /* 解析词组（词库优先） */
        char phrases[8][16];
        size_t np = json_parse_phrases(response, phrases, 8);
        for (size_t i = 0; i < np; i++) {
            if (strlen(phrases[i]) == 0) continue;
            CWCandidate *cand = [[CWCandidate alloc] init];
            cand.phrase = [NSString stringWithUTF8String:phrases[i]];
            cand.isPhrase = YES;
            [(NSMutableArray *)result.candidates addObject:cand];
        }
        /* 解析单字 */
        uint32_t cps[16];
        size_t nc = json_parse_candidates(response, cps, 16);
        for (size_t i = 0; i < nc; i++) {
            if (cps[i] == 0) continue;
            CWCandidate *cand = [[CWCandidate alloc] init];
            cand.phrase = [self unicodeChar:cps[i]];
            cand.isPhrase = NO;
            [(NSMutableArray *)result.candidates addObject:cand];
        }
    }

    return result;
}

- (void)reportSelection:(NSString *)phrase
{
    if (phrase.length == 0) return;
    char body[128];
    snprintf(body, sizeof(body), "{\"learn\":\"%s\"}", [phrase UTF8String]);
    char response[1024];
    /* 上报失败不影响主流程（云端学习尽力而为） */
    (void)http_post([_gatewayHost UTF8String], _gatewayPort, [_gatewayPath UTF8String],
                    body, response, sizeof(response));
}

#pragma mark - 辅助

- (NSString *)unicodeChar:(uint32_t)cp
{
    if (cp == 0) return @"";
    /* UTF-8 编码 */
    char buf[5] = {0};
    if (cp < 0x80) {
        buf[0] = (char)cp;
    } else if (cp < 0x800) {
        buf[0] = (char)(0xC0 | (cp >> 6));
        buf[1] = (char)(0x80 | (cp & 0x3F));
    } else if (cp < 0x10000) {
        buf[0] = (char)(0xE0 | (cp >> 12));
        buf[1] = (char)(0x80 | ((cp >> 6) & 0x3F));
        buf[2] = (char)(0x80 | (cp & 0x3F));
    }
    return [NSString stringWithUTF8String:buf];
}

@end
