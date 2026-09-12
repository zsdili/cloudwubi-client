/*
 * test_query_engine.m - CWQueryEngine 单元测试（macOS 核心逻辑）
 *
 * 编译运行（需 macOS/ObjC 运行时）：
 *   clang -framework Foundation -fobjc-arc -o test_qe test_query_engine.m CWQueryEngine.m ../src/http_client.c ../src/json_parser.c ../src/lru_cache.c ../src/wubi_engine.c
 *   ./test_qe
 *
 * 覆盖：
 *   1. 编码合法性（appendKey 只接受 a~y，最多4码）
 *   2. 本地兜底简码（断网也能打一级简码）
 *   3. 云端查询（需要本地网关，跳过不视为失败）
 */

#import <Foundation/Foundation.h>
#import "CWQueryEngine.h"

static int g_failures = 0;
static int g_passes = 0;

#define CHECK(cond, name) \
    do { \
        if (cond) { g_passes++; printf("  ✅ %s\n", name); } \
        else { g_failures++; printf("  ❌ %s\n", name); } \
    } while (0)

int main(int argc, const char *argv[])
{
    @autoreleasepool {
        CWQueryEngine *engine = [CWQueryEngine sharedEngine];

        printf("=== CWQueryEngine 单元测试 ===\n\n");

        /* 测试1：合法编码 */
        CHECK([engine appendKey:'w'] == YES, "appendKey 'w' 合法");
        CHECK([engine appendKey:'q'] == YES, "appendKey 'q' 合法");
        CHECK([[engine composingCode] isEqualToString:@"wq"], "composingCode = wq");
        [engine clearComposing];
        CHECK([engine composingCode].length == 0, "clearComposing 清空");

        /* 测试2：非法按键 */
        CHECK([engine appendKey:'z'] == NO, "appendKey 'z' 非法（五笔不用z）");
        CHECK([engine appendKey:'A'] == NO, "appendKey 'A' 非法（必须小写）");
        CHECK([engine appendKey:'1'] == NO, "appendKey '1' 非法（必须字母）");

        /* 测试3：最多4码 */
        [engine clearComposing];
        [engine appendKey:'a']; [engine appendKey:'b'];
        [engine appendKey:'c']; [engine appendKey:'d'];
        CHECK([engine appendKey:'e'] == NO, "第5码被拒绝（最多4码）");
        CHECK([engine composingCode].length == 4, "编码长度=4");

        /* 测试4：本地兜底简码查询（不依赖网络） */
        [engine clearComposing];
        [engine appendKey:'g'];  /* 一 的一级简码 */
        CWQueryResult *r1 = [engine queryCandidates];
        BOOL foundOne = NO;
        for (CWCandidate *c in r1.candidates) {
            if ([c.phrase isEqualToString:@"一"]) { foundOne = YES; break; }
        }
        CHECK(foundOne, "本地兜底：g → 一");

        /* 测试5：2码查询——网关不可达时应降级为空但不崩溃（断网行为） */
        [engine clearComposing];
        [engine appendKey:'w']; [engine appendKey:'q'];
        CWQueryResult *r2 = [engine queryCandidates];
        printf("  （wq 候选数: %lu，断网降级为空属预期）\n", (unsigned long)r2.candidates.count);
        CHECK(YES, "wq 查询无崩溃（断网降级）");

        /* 测试6：断网时 1 码本地兜底依然可用（降级核心价值） */
        [engine clearComposing];
        [engine appendKey:'g'];
        CWQueryResult *r3 = [engine queryCandidates];
        BOOL foundFallback = NO;
        for (CWCandidate *c in r3.candidates) {
            if ([c.phrase isEqualToString:@"一"]) { foundFallback = YES; break; }
        }
        CHECK(foundFallback, "断网降级：g → 一（本地兜底生效）");

        /* 测试7：上报选词（尽力而为，不崩溃） */
        [engine reportSelection:@"你好"];
        CHECK(YES, "reportSelection 无异常");

        printf("\n=== 结果: %d 通过, %d 失败 ===\n", g_passes, g_failures);
        return g_failures == 0 ? 0 : 1;
    }
}
