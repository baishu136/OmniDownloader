Set ws = CreateObject("WScript.Shell")
Set fso = CreateObject("Scripting.FileSystemObject")
dir = fso.GetParentFolderName(WScript.ScriptFullName)
ws.CurrentDirectory = dir

exePath = dir & "\dist\OmniDownloaderWeb\OmniDownloaderWeb.exe"
pythonwPath = "C:\Users\GUDGA\AppData\Local\Python\pythoncore-3.14-64\pythonw.exe"

If fso.FileExists(exePath) Then
    ws.Run """" & exePath & """", 0, False
ElseIf fso.FileExists(pythonwPath) Then
    ws.Run """" & pythonwPath & """ omni_tray.py", 0, False
Else
    ws.Run "pythonw omni_tray.py", 0, False
End If
