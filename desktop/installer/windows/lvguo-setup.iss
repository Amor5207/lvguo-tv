; 绿果PC Windows 安装向导脚本 (Inno Setup 6)
#ifndef Arch
  #define Arch "x64"
#endif

#define MyAppName "绿果PC"
#ifndef AppVersion
  #define MyAppVersion "2.1.0"
#else
  #define MyAppVersion AppVersion
#endif
#define MyAppPublisher "Pengfei"
#define MyAppExeName "lvguo_desktop.exe"
#define ReleaseDir "..\..\build\windows\" + Arch + "\runner\Release"

[Setup]
AppId={{8D6E4A21-7C1B-4E2A-9B0F-3A91D48F2011}
AppName={#MyAppName}
AppVersion={#MyAppVersion}
AppVerName={#MyAppName} {#MyAppVersion}
AppPublisher={#MyAppPublisher}
DefaultDirName={localappdata}\Programs\LvguoPC
DefaultGroupName={#MyAppName}
AllowNoIcons=yes
DisableProgramGroupPage=yes
DisableDirPage=no
ShowLanguageDialog=no
OutputDir=..\..\dist
OutputBaseFilename=LvguoPC-Setup-{#MyAppVersion}-win-{#Arch}
SetupIconFile=..\..\windows\runner\resources\app_icon.ico
Compression=lzma2/ultra64
SolidCompression=yes
WizardStyle=modern
#if Arch == "arm64"
ArchitecturesAllowed=arm64
ArchitecturesInstallIn64BitMode=arm64
#else
ArchitecturesAllowed=x64compatible
ArchitecturesInstallIn64BitMode=x64compatible
#endif
PrivilegesRequired=lowest
PrivilegesRequiredOverridesAllowed=dialog
UninstallDisplayIcon={app}\{#MyAppExeName}
UninstallDisplayName={#MyAppName}
UsePreviousTasks=no

[Languages]
Name: "chinesesimp"; MessagesFile: "compiler:Default.isl,ChineseSimplified.isl"

[Tasks]
Name: "desktopicon"; Description: "创建桌面快捷方式(&D)"; GroupDescription: "附加任务:"
Name: "startup"; Description: "开机自动启动 {#MyAppName}(&S)"; GroupDescription: "附加任务:"; Flags: unchecked
Name: "protocol"; Description: "注册 lvguo:// 桌面协议"; GroupDescription: "附加任务:"

[Files]
Source: "{#ReleaseDir}\*"; DestDir: "{app}"; Flags: ignoreversion recursesubdirs createallsubdirs

[Icons]
Name: "{autoprograms}\{#MyAppName}"; Filename: "{app}\{#MyAppExeName}"
Name: "{autodesktop}\{#MyAppName}"; Filename: "{app}\{#MyAppExeName}"; Tasks: desktopicon

[Registry]
Root: HKCU; Subkey: "Software\Microsoft\Windows\CurrentVersion\Run"; ValueType: string; ValueName: "LvguoPC"; ValueData: """{app}\{#MyAppExeName}"""; Flags: uninsdeletevalue; Tasks: startup
Root: HKCU; Subkey: "Software\Classes\lvguo"; ValueType: string; ValueData: "URL:LvguoPC Protocol"; Flags: uninsdeletekey; Tasks: protocol
Root: HKCU; Subkey: "Software\Classes\lvguo"; ValueType: string; ValueName: "URL Protocol"; ValueData: ""; Flags: uninsdeletekey; Tasks: protocol
Root: HKCU; Subkey: "Software\Classes\lvguo\DefaultIcon"; ValueType: string; ValueData: "{app}\{#MyAppExeName},0"; Flags: uninsdeletekey; Tasks: protocol
Root: HKCU; Subkey: "Software\Classes\lvguo\shell\open\command"; ValueType: string; ValueData: """{app}\{#MyAppExeName}"" ""%1"""; Flags: uninsdeletekey; Tasks: protocol

[Run]
Filename: "{app}\{#MyAppExeName}"; Description: "立即启动 {#MyAppName}"; Flags: nowait postinstall skipifsilent

[Code]
function InitializeSetup(): Boolean;
begin
  Result := True;
end;

procedure CurUninstallStepChanged(CurUninstallStep: TUninstallStep);
begin
  // 卸载程序仅移除客户端安装目录与注册表项，保留用户下载目录中的视频与任务配置
end;
