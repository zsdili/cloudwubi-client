/*
 * CloudWubiController.m - CloudWubi macOS 输入法控制器实现
 *
 * 核心流程：
 *   1. 用户按键（字母 a~y）→ appendKey 到查询引擎
 *   2. 编码变化 → 查询候选 → 更新候选窗口（IMKCandidates）
 *   3. 用户选候选 → commitComposition 上屏 → reportSelection 云端学习
 *   4. Esc/回车 处理
 *
 * 注意：本文件为 UI 薄层，全部查询逻辑在 CWQueryEngine（已单测）。
 */

#import "CloudWubiController.h"
#import <AppKit/AppKit.h>

@implementation CloudWubiController

- (instancetype)initWithServer:(IMKServer *)server delegate:(id)delegate client:(id)inputClient
{
    self = [super initWithServer:server delegate:delegate client:inputClient];
    if (self) {
        _engine = [CWQueryEngine sharedEngine];

        /* 候选窗口：一行平铺（五笔习惯） */
        _candidateWindow = [[IMKCandidates alloc] initWithServer:server
                                                panelType:kIMKScrollingGridCandidatePanel];
        [_candidateWindow setAttributes:@{
            IMKCandidatesSendServerKeyEventFirst: @YES,
        }];
    }
    return self;
}

#pragma mark - 键盘事件

- (BOOL)handleEvent:(NSEvent *)event client:(id)sender
{
    if (event.type != NSEventTypeKeyDown) return NO;

    NSString *chars = event.charactersIgnoringModifiers;
    if (chars.length == 0) return NO;
    unichar c = [chars characterAtIndex:0];

    /* 退格：删除最后一位编码 */
    if (c == 0x7F) {  /* Delete */
        if (_engine.composingCode.length > 0) {
            [_engine clearComposing];
            [_candidateWindow hide];
        }
        return YES;
    }

    /* Esc：取消 */
    if (c == 0x1B) {
        [_engine clearComposing];
        [_candidateWindow hide];
        return YES;
    }

    /* 数字选择候选（0~8） */
    if (c >= '0' && c <= '8') {
        NSArray *cands = [self currentCandidates];
        NSUInteger idx = (NSUInteger)(c - '0');
        if (idx < cands.count) {
            [self selectCandidateAtIndex:idx];
        }
        return YES;
    }

    /* 空格：选第一个候选（如果有） */
    if (c == ' ') {
        NSArray *cands = [self currentCandidates];
        if (cands.count > 0) {
            [self selectCandidateAtIndex:0];
        }
        return YES;
    }

    /* 五笔字母 a~y：追加编码 */
    if (c >= 'a' && c <= 'y') {
        if ([_engine appendKey:(char)c]) {
            [self refreshCandidates:sender];
        }
        return YES;
    }

    /* 其他按键：若有未确认编码，先清除（避免吞字） */
    if (_engine.composingCode.length > 0) {
        [_engine clearComposing];
        [_candidateWindow hide];
    }
    return NO;
}

#pragma mark - 候选

- (NSArray *)currentCandidates
{
    CWQueryResult *result = [_engine queryCandidates];
    NSMutableArray *items = [NSMutableArray array];
    for (CWCandidate *c in result.candidates) {
        [items addObject:c.phrase];
    }
    return items;
}

- (void)refreshCandidates:(id)sender
{
    NSArray *cands = [self currentCandidates];
    if (cands.count == 0) {
        [_candidateWindow hide];
        return;
    }
    [_candidateWindow updateCandidates:cands];
    [_candidateWindow show:kIMKLocateCandidatesBelowHighlight];
}

- (void)selectCandidateAtIndex:(NSUInteger)idx
{
    NSArray *cands = [self currentCandidates];
    if (idx >= cands.count) return;

    NSString *selected = cands[idx];
    [self commitText:selected];
    [_engine reportSelection:selected];  /* 云端 MRU 学习 */
}

- (void)commitText:(NSString *)text
{
    id client = [self client];
    if ([client respondsToSelector:@selector(insertText:replacementRange:)]) {
        [client insertText:text replacementRange:NSMakeRange(NSNotFound, NSNotFound)];
    }
    [_engine clearComposing];
    [_candidateWindow hide];
}

#pragma mark - IMK 协议

- (void)commitComposition:(id)sender
{
    if (_engine.composingCode.length > 0) {
        [self commitText:[self currentCandidates].firstObject ?: @""];
    }
}

- (void)candidates:(id)sender
{
    [_candidateWindow show:kIMKLocateCandidatesBelowHighlight];
}

@end
