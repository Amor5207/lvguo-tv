; *** Inno Setup 6.5.0+ Simplified Chinese Messages ***

[LangOptions]
LanguageName=简体中文
LanguageID=$0804
LanguageCodePage=936
DialogFontName=Microsoft YaHei UI
DialogFontSize=9
WelcomeFontName=Microsoft YaHei UI
WelcomeFontSize=12
TitleFontName=Microsoft YaHei UI
TitleFontSize=29
CopyrightFontName=Microsoft YaHei UI
CopyrightFontSize=9

[Messages]

; *** Application titles
SetupAppTitle=安装程序
SetupWindowTitle=安装 - %1
UninstallAppTitle=卸载
UninstallAppFullTitle=卸载 %1

; *** Misc. common
InformationTitle=提示
ConfirmTitle=确认
ErrorTitle=错误

; *** SetupLdr messages
SetupLdrStartupMessage=即将安装 %1。是否继续？
LdrCannotCreateTemp=无法创建临时文件，安装已中止
LdrCannotExecTemp=无法在临时目录中执行文件，安装已中止
HelpTextNote=

; *** Startup error messages
LastErrorMessage=%1.%n%n错误 %2: %3
SetupFileMissing=安装目录中缺少文件 %1。请修复问题或重新获取安装程序。
SetupFileCorrupt=安装文件已损坏。请重新获取安装程序。
SetupFileCorruptOrWrongVer=安装文件已损坏，或与当前安装程序版本不兼容。请修复问题或重新获取安装程序。
InvalidParameter=命令行参数无效:%n%n%1
SetupAlreadyRunning=安装程序已在运行。
WindowsVersionNotSupported=此程序不支持当前计算机运行的 Windows 版本。
WindowsServicePackRequired=此程序需要 %1 Service Pack %2 或更高版本。
NotOnThisPlatform=此程序无法在 %1 上运行。
OnlyOnThisPlatform=此程序必须在 %1 上运行。
OnlyOnTheseArchitectures=此程序仅支持为以下处理器架构设计的 Windows 版本:%n%n%1
WinVersionTooLowError=此程序需要 %1 %2 或更高版本。
WinVersionTooHighError=此程序无法安装在 %1 %2 或更高版本上。
AdminPrivilegesRequired=安装此程序需要以管理员身份登录。
PowerUserPrivilegesRequired=安装此程序需要以管理员或 Power Users 组成员身份登录。
SetupAppRunningError=安装程序检测到 %1 正在运行。%n%n请先关闭所有运行中的窗口，然后点击“确定”继续，或点击“取消”退出。
UninstallAppRunningError=卸载程序检测到 %1 正在运行。%n%n请先关闭所有运行中的窗口，然后点击“确定”继续，或点击“取消”退出。

; *** Startup questions
PrivilegesRequiredOverrideTitle=选择安装模式
PrivilegesRequiredOverrideInstruction=选择安装模式
PrivilegesRequiredOverrideText1=%1 可以为所有用户安装（需要管理员权限），或仅为您当前用户安装。
PrivilegesRequiredOverrideText2=%1 可以仅为您当前用户安装，或为所有用户安装（需要管理员权限）。
PrivilegesRequiredOverrideAllUsers=为所有用户安装(&A)
PrivilegesRequiredOverrideAllUsersRecommended=为所有用户安装（推荐）(&A)
PrivilegesRequiredOverrideCurrentUser=仅为我安装(&M)
PrivilegesRequiredOverrideCurrentUserRecommended=仅为我安装（推荐）(&M)

; *** Misc. errors
ErrorCreatingDir=安装程序无法创建目录“%1”
ErrorTooManyFilesInDir=无法在目录“%1”中创建文件，因为该目录包含的文件过多

; *** Setup common messages
ExitSetupTitle=退出安装程序
ExitSetupMessage=安装尚未完成。如果现在退出，程序将不会被安装。%n%n您可以稍后再次运行安装程序以完成安装。%n%n确定要退出安装吗？
AboutSetupMenuItem=关于安装程序(&A)...
AboutSetupTitle=关于安装程序
AboutSetupMessage=%1 版本 %2%n%3%n%n%1 主页:%n%4
AboutSetupNote=
TranslatorNote=

; *** Buttons
ButtonBack=< 上一步(&B)
ButtonNext=下一步(&N) >
ButtonInstall=安装(&I)
ButtonOK=确定
ButtonCancel=取消
ButtonYes=是(&Y)
ButtonYesToAll=全是(&A)
ButtonNo=否(&N)
ButtonNoToAll=全否(&O)
ButtonFinish=完成(&F)
ButtonBrowse=浏览(&R)...
ButtonWizardBrowse=浏览(&R)...
ButtonNewFolder=新建文件夹(&M)

; *** "Select Language" dialog messages
SelectLanguageTitle=选择安装语言
SelectLanguageLabel=请选择安装过程中使用的语言。

; *** Common wizard text
ClickNext=点击“下一步”继续，或点击“取消”退出安装程序。
BeveledLabel=
BrowseDialogTitle=浏览文件夹
BrowseDialogLabel=请在下方列表中选择一个文件夹，然后点击“确定”。
NewFolderName=新建文件夹

; *** "Welcome" wizard page
WelcomeLabel1=欢迎使用 [name] 安装向导
WelcomeLabel2=这将在您的计算机上安装 [name/ver]。%n%n建议您在继续前关闭所有其他应用程序。

; *** "Password" wizard page
WizardPassword=密码
PasswordLabel1=此安装受密码保护。
PasswordLabel3=请输入密码，然后点击“下一步”继续。密码区分大小写。
PasswordEditLabel=密码(&P):
IncorrectPassword=输入的密码不正确，请重试。

; *** "License Agreement" wizard page
WizardLicense=许可协议
LicenseLabel=在继续之前，请阅读以下重要信息。
LicenseLabel3=请阅读以下许可协议。在继续安装之前，您必须接受此协议的条款。
LicenseAccepted=我接受此协议(&A)
LicenseNotAccepted=我不接受此协议(&D)

; *** "Information" wizard pages
WizardInfoBefore=重要信息
InfoBeforeLabel=在继续之前，请阅读以下重要信息。
InfoBeforeClickLabel=准备好继续安装后，请点击“下一步”。
WizardInfoAfter=重要信息
InfoAfterLabel=在继续之前，请阅读以下重要信息。
InfoAfterClickLabel=准备好继续安装后，请点击“下一步”。

; *** "User Information" wizard page
WizardUserInfo=用户信息
UserInfoDesc=请输入您的信息。
UserInfoName=用户名(&U):
UserInfoOrg=组织(&O):
UserInfoSerial=序列号(&S):
UserInfoNameRequired=您必须输入用户名。

; *** "Select Destination Location" wizard page
WizardSelectDir=选择目标位置
SelectDirDesc=将 [name] 安装到何处？
SelectDirLabel3=安装程序将把 [name] 安装到以下文件夹中。
SelectDirBrowseLabel=点击“下一步”继续。如果您希望选择其他文件夹，请点击“浏览”。
DiskSpaceGBLabel=至少需要 [gb] GB 的可用磁盘空间。
DiskSpaceMBLabel=至少需要 [mb] MB 的可用磁盘空间。
CannotInstallToNetworkDrive=安装程序无法安装到网络驱动器。
CannotInstallToUNCPath=安装程序无法安装到 UNC 路径。
InvalidPath=您必须输入包含盘符的完整路径；例如:%n%nC:\APP%n%n或如下格式的 UNC 路径:%n%n\\server\share
InvalidDrive=您选择的驱动器或 UNC 共享不存在或无法访问，请选择其他位置。
DiskSpaceWarningTitle=磁盘空间不足
DiskSpaceWarning=安装程序至少需要 %1 KB 的可用空间，但所选驱动器仅有 %2 KB 可用。%n%n您确定仍要继续吗？
DirNameTooLong=文件夹名称或路径过长。
InvalidDirName=文件夹名称无效。
BadDirName32=文件夹名称不能包含以下任意字符:%n%n%1
DirExistsTitle=文件夹已存在
DirExists=文件夹:%n%n%1%n%n已存在。确定仍要安装到该文件夹吗？
DirDoesntExistTitle=文件夹不存在
DirDoesntExist=文件夹:%n%n%1%n%n不存在。是否立即创建该文件夹？

; *** "Select Components" wizard page
WizardSelectComponents=选择组件
SelectComponentsDesc=需要安装哪些组件？
SelectComponentsLabel2=选择您要安装的组件，取消勾选不需要的组件，完成后点击“下一步”。
FullInstallation=完全安装
CompactInstallation=精简安装
CustomInstallation=自定义安装
NoUninstallWarningTitle=组件已存在
NoUninstallWarning=安装程序检测到您的计算机上已安装以下组件:%n%n%1%n%n取消选择这些组件不会卸载它们。%n%n您确定要继续吗？
ComponentSize1=%1 KB
ComponentSize2=%1 MB
ComponentsDiskSpaceGBLabel=当前选择至少需要 [gb] GB 磁盘空间。
ComponentsDiskSpaceMBLabel=当前选择至少需要 [mb] MB 磁盘空间。

; *** "Select Additional Tasks" wizard page
WizardSelectTasks=选择附加任务
SelectTasksDesc=需要执行哪些附加任务？
SelectTasksLabel2=选择安装 [name] 时要执行的附加任务，然后点击“下一步”。

; *** "Select Start Menu Folder" wizard page
WizardSelectProgramGroup=选择开始菜单文件夹
SelectStartMenuFolderDesc=将在何处创建程序快捷方式？
SelectStartMenuFolderLabel3=安装程序将在以下“开始菜单”文件夹中创建快捷方式。
SelectStartMenuFolderBrowseLabel=点击“下一步”继续。如果要选择其他文件夹，请点击“浏览”。
MustEnterGroupName=必须输入文件夹名称。
GroupNameTooLong=文件夹名称或路径过长。
InvalidGroupName=文件夹名称无效。
BadGroupName=文件夹名称不能包含以下任意字符:%n%n%1
NoProgramGroupCheck2=不创建开始菜单文件夹(&D)

; *** "Ready to Install" wizard page
WizardReady=准备安装
ReadyLabel1=安装程序已准备好在您的计算机上安装 [name]。
ReadyLabel2a=点击“安装”开始安装，若需查看或更改设置，请点击“上一步”。
ReadyLabel2b=点击“安装”开始安装。
ReadyMemoUserInfo=用户信息:
ReadyMemoDir=目标位置:
ReadyMemoType=安装类型:
ReadyMemoComponents=已选组件:
ReadyMemoGroup=开始菜单文件夹:
ReadyMemoTasks=附加任务:

; *** TDownloadWizardPage wizard page and DownloadTemporaryFile
DownloadingLabel2=正在下载文件...
ButtonStopDownload=停止下载(&S)
StopDownload=确定要停止下载吗？
ErrorDownloadAborted=下载已中止
ErrorDownloadFailed=下载失败: %1 %2
ErrorDownloadSizeFailed=获取大小失败: %1 %2
ErrorProgress=进度无效: %1 / %2
ErrorFileSize=文件大小无效: 预期 %1，实际 %2

; *** TExtractionWizardPage wizard page and ExtractArchive
ExtractingLabel=正在解压文件...
ButtonStopExtraction=停止解压(&S)
StopExtraction=确定要停止解压吗？
ErrorExtractionAborted=解压已中止
ErrorExtractionFailed=解压失败: %1

; *** Archive extraction failure details
ArchiveIncorrectPassword=密码不正确
ArchiveIsCorrupted=压缩包已损坏
ArchiveUnsupportedFormat=不支持的压缩包格式

; *** "Preparing to Install" wizard page
WizardPreparing=准备安装
PreparingDesc=正在准备在您的计算机上安装 [name]。
PreviousInstallNotCompleted=此前程序的安装/卸载尚未完成。您需要重启计算机以完成操作。%n%n重启计算机后，请再次运行安装程序以完成 [name] 的安装。
CannotContinue=无法继续安装。请点击“取消”退出。
ApplicationsFound=以下应用程序正在使用需要更新的文件。建议允许安装程序自动关闭这些应用程序。
ApplicationsFound2=以下应用程序正在使用需要更新的文件。建议允许安装程序自动关闭这些应用程序，安装完成后将尝试重新启动它们。
CloseApplications=自动关闭应用程序(&A)
DontCloseApplications=不关闭应用程序(&D)
ErrorCloseApplications=安装程序无法自动关闭所有应用程序。建议您在继续之前手动关闭占用文件的程序。
PrepareToInstallNeedsRestart=必须重启计算机。重启后请重新运行安装程序以完成 [name] 的安装。%n%n是否立即重启？

; *** "Installing" wizard page
WizardInstalling=正在安装
InstallingLabel=正在计算机上安装 [name]，请稍候。

; *** "Setup Completed" wizard page
FinishedHeadingLabel=[name] 安装向导已完成
FinishedLabelNoIcons=[name] 已成功安装到您的计算机。
FinishedLabel=[name] 已成功安装到您的计算机。您可以通过桌面或开始菜单快捷方式启动应用。
ClickFinish=点击“完成”退出安装程序。
FinishedRestartLabel=必须重启计算机才能完成 [name] 的安装。是否立即重启？
FinishedRestartMessage=必须重启计算机才能完成 [name] 的安装。%n%n是否立即重启？
ShowReadmeCheck=查看自述文件 (README)
YesRadio=是，立即重启计算机(&Y)
NoRadio=否，稍后手动重启(&N)
RunEntryExec=启动 %1
RunEntryShellExec=查看 %1

; *** "Setup Needs the Next Disk" stuff
ChangeDiskTitle=安装程序需要下一张磁盘
SelectDiskLabel2=请插入第 %1 张磁盘并点击“确定”。%n%n如果文件位于下方显示的文件夹之外，请输入正确路径或点击“浏览”。
PathLabel=路径(&P):
FileNotInDir2=在“%2”中找不到文件“%1”。请插入正确的磁盘或选择其他文件夹。
SelectDirectoryLabel=请指定下一张磁盘的位置。

; *** Installation phase messages
SetupAborted=安装未能完成。%n%n请修正问题后重新运行安装程序。
AbortRetryIgnoreSelectAction=选择操作
AbortRetryIgnoreRetry=重试(&T)
AbortRetryIgnoreIgnore=忽略错误并继续(&I)
AbortRetryIgnoreCancel=取消安装
RetryCancelSelectAction=选择操作
RetryCancelRetry=重试(&T)
RetryCancelCancel=取消

; *** Installation status messages
StatusClosingApplications=正在关闭应用程序...
StatusCreateDirs=正在创建目录...
StatusExtractFiles=正在释放文件...
StatusDownloadFiles=正在下载文件...
StatusCreateIcons=正在创建快捷方式...
StatusCreateIniEntries=正在写入 INI 配置...
StatusCreateRegistryEntries=正在写入注册表项...
StatusRegisterFiles=正在注册组件...
StatusSavingUninstall=正在保存卸载信息...
StatusRunProgram=正在完成安装...
StatusRestartingApplications=正在重启应用程序...
StatusRollback=正在回滚更改...

; *** Misc. errors
ErrorInternal2=内部错误: %1
ErrorFunctionFailedNoCode=%1 失败
ErrorFunctionFailed=%1 失败；错误码 %2
ErrorFunctionFailedWithMessage=%1 失败；错误码 %2。%n%3
ErrorExecutingProgram=无法执行文件:%n%1

; *** Registry errors
ErrorRegOpenKey=打开注册表项出错:%n%1\%2
ErrorRegCreateKey=创建注册表项出错:%n%1\%2
ErrorRegWriteKey=写入注册表项出错:%n%1\%2

; *** INI errors
ErrorIniEntry=在文件“%1”中写入 INI 条目出错。

; *** File copying errors
FileAbortRetryIgnoreSkipNotRecommended=跳过此文件（不推荐）(&S)
FileAbortRetryIgnoreIgnoreNotRecommended=忽略错误并继续（不推荐）(&I)
SourceIsCorrupted=源文件已损坏
SourceDoesntExist=源文件“%1”不存在
SourceVerificationFailed=源文件校验失败: %1
VerificationSignatureDoesntExist=签名文件“%1”不存在
VerificationSignatureInvalid=签名文件“%1”无效
VerificationKeyNotFound=签名文件“%1”使用了未知密钥
VerificationFileNameIncorrect=文件名不正确
VerificationFileTagIncorrect=文件标签不正确
VerificationFileSizeIncorrect=文件大小不正确
VerificationFileHashIncorrect=文件哈希不正确
ExistingFileReadOnly2=无法替换现有文件，因为其属性为只读。
ExistingFileReadOnlyRetry=移除只读属性并重试(&R)
ExistingFileReadOnlyKeepExisting=保留现有文件(&K)
ErrorReadingExistingDest=读取现有文件时出错:
FileExistsSelectAction=选择操作
FileExists2=文件已存在。
FileExistsOverwriteExisting=覆盖现有文件(&O)
FileExistsKeepExisting=保留现有文件(&K)
FileExistsOverwriteOrKeepAll=对后续冲突执行相同操作(&D)
ExistingFileNewerSelectAction=选择操作
ExistingFileNewer2=现有文件比安装程序要安装的文件更新。
ExistingFileNewerOverwriteExisting=覆盖现有文件(&O)
ExistingFileNewerKeepExisting=保留现有文件（推荐）(&K)
ExistingFileNewerOverwriteOrKeepAll=对后续冲突执行相同操作(&D)
ErrorChangingAttr=更改现有文件属性时出错:
ErrorCreatingTemp=在目标目录创建临时文件时出错:
ErrorReadingSource=读取源文件时出错:
ErrorCopying=复制文件时出错:
ErrorDownloading=下载文件时出错:
ErrorExtracting=解压文件时出错:
ErrorReplacingExistingFile=替换现有文件时出错:
ErrorRestartReplace=重启后替换失败:
ErrorRenamingTemp=重命名目标目录中的文件时出错:
ErrorRegisterServer=无法注册 DLL/OCX: %1
ErrorRegSvr32Failed=RegSvr32 失败，退出码 %1
ErrorRegisterTypeLib=无法注册类型库: %1

; *** Uninstall display name markings
UninstallDisplayNameMark=%1 (%2)
UninstallDisplayNameMarks=%1 (%2, %3)
UninstallDisplayNameMark32Bit=32 位
UninstallDisplayNameMark64Bit=64 位
UninstallDisplayNameMarkAllUsers=所有用户
UninstallDisplayNameMarkCurrentUser=当前用户

; *** Post-installation errors
ErrorOpeningReadme=打开自述文件时出错。
ErrorRestartingComputer=安装程序无法重启计算机，请手动重启。

; *** Uninstaller messages
UninstallNotFound=文件“%1”不存在，无法卸载。
UninstallOpenError=无法打开文件“%1”，无法卸载
UninstallUnsupportedVer=当前卸载程序版本无法识别卸载日志文件“%1”的格式，无法卸载
UninstallUnknownEntry=卸载日志中存在未知条目 (%1)
ConfirmUninstall=确定要完全移除 %1 及其所有组件吗？
UninstallOnlyOnWin64=此安装只能在 64 位 Windows 上卸载。
OnlyAdminCanUninstall=只有具有管理员权限的用户才能卸载此程序。
UninstallStatusLabel=正在从您的计算机中移除 %1，请稍候。
UninstalledAll=已成功从您的计算机中移除 %1。
UninstalledMost=%1 卸载完成。%n%n部分文件未能移除，您可以手动删除。
UninstalledAndNeedsRestart=必须重启计算机才能完成 %1 的卸载。%n%n是否立即重启？
UninstallDataCorrupted=文件“%1”已损坏，无法卸载

; *** Uninstallation phase messages
ConfirmDeleteSharedFileTitle=移除共享文件？
ConfirmDeleteSharedFile2=系统提示以下共享文件已不再被任何程序使用。是否移除该共享文件？%n%n如果仍有其他程序依赖此文件，移除后可能导致其无法正常运行。如不确定请选择“否”，保留文件不会造成影响。
SharedFileNameLabel=文件名:
SharedFileLocationLabel=位置:
WizardUninstalling=卸载状态
StatusUninstalling=正在卸载 %1...

; *** Shutdown block reasons
ShutdownBlockReasonInstallingApp=正在安装 %1。
ShutdownBlockReasonUninstallingApp=正在卸载 %1。

[CustomMessages]

NameAndVersion=%1 版本 %2
AdditionalIcons=附加快捷方式:
CreateDesktopIcon=创建桌面快捷方式(&D)
CreateQuickLaunchIcon=创建快速启动快捷方式(&Q)
ProgramOnTheWeb=访问 %1 主页
UninstallProgram=卸载 %1
LaunchProgram=运行 %1
AssocFileExtension=将 %2 文件与 %1 关联(&A)
AssocingFileExtension=正在将 %2 文件与 %1 关联...
AutoStartProgramGroupDescription=启动项:
AutoStartProgram=自动启动 %1
AddonHostProgramNotFound=无法在所选文件夹中找到 %1。%n%n确定要继续吗？
