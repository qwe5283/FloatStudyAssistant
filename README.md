# 探索学X通线上考试过检测搜题（非root方案）

现象：大学搜X酱的悬浮窗搜题使用安卓系统提供的媒体投影API（android.media.projection）读取屏幕，并通过OCR提取文字用于搜题。

问题：学X通在考试时会也会抢占媒体投影API进行屏幕抓拍（大约5分钟一次），且使用FLAG_SECURE保护自己的考试界面（例如防止媒体投影API、ADB投屏）

解决思路：改用无障碍服务读取考试界面内容，并使用ADB权限（Shizuku）高频读取窗口快照（dumpsys window windows）来统计FLAG_SECURE窗口数量，当FLAG_SECURE窗口数量减少时，隐藏自己的悬浮窗，从而避免被屏幕抓拍捕获。

在`docs/2月23日(1).mp4`查看实现效果

