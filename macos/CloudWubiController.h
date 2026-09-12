/*
 * CloudWubiController.h - CloudWubi macOS 输入法控制器
 *
 * InputMethodKit 输入法主控制器：
 *   - 接收键盘事件，把 a~y 字母送入查询引擎
 *   - 显示候选窗口（IMKCandidates）
 *   - 确认/取消时上屏或清除
 */

#import <InputMethodKit/InputMethodKit.h>
#import "CWQueryEngine.h"

@interface CloudWubiController : IMKInputController
{
    IMKCandidates *_candidateWindow;
    CWQueryEngine *_engine;
}
@end
