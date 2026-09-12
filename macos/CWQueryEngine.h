/*
 * CWQueryEngine.h - CloudWubi macOS 输入法核心查询引擎（可测试纯逻辑层）
 *
 * 设计原则（先科学后先进）：
 *   - 查询逻辑与 UI 完全分离，便于单元测试
 *   - 复用 C 内核模块（wubi_engine / http_client / json_parser）
 *   - 支持离线降级（断网时用本地一级简码兜底）
 */

#import <Foundation/Foundation.h>

NS_ASSUME_NONNULL_BEGIN

/* 单个候选 */
@interface CWCandidate : NSObject
@property (nonatomic, strong) NSString *phrase;   /* 候选文本（汉字或词组） */
@property (nonatomic, assign) BOOL isPhrase;      /* YES=词组，NO=单字 */
@end

/* 查询结果 */
@interface CWQueryResult : NSObject
@property (nonatomic, strong) NSString *code;                 /* 输入编码 */
@property (nonatomic, strong) NSArray<CWCandidate *> *candidates; /* 候选列表 */
@end

/* 查询引擎 */
@interface CWQueryEngine : NSObject

/* 云端网关配置（部署后替换为真实地址；默认本地演示） */
@property (nonatomic, copy) NSString *gatewayHost;
@property (nonatomic, assign) int gatewayPort;
@property (nonatomic, copy) NSString *gatewayPath;

/* 当前已输入的编码串（1~4 码） */
@property (nonatomic, readonly) NSString *composingCode;

/* 单例 */
+ (instancetype)sharedEngine;

/* 按键处理：追加一个字母（a~y）到编码串；非法按键返回 NO */
- (BOOL)appendKey:(char)c;

/* 清除当前编码串（候选确认/取消时调用） */
- (void)clearComposing;

/* 查询候选（同步；内部联网，断网时自动降级本地） */
- (CWQueryResult *)queryCandidates;

/* 上报用户选词（云端 MRU 学习） */
- (void)reportSelection:(NSString *)phrase;

@end

NS_ASSUME_NONNULL_END
