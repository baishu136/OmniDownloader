using System.ComponentModel;
using System.Runtime.InteropServices;
using System.Runtime.InteropServices.ComTypes;
using System.Text;
using OmniDownloader.WinUI.Core.Models;
using OmniDownloader.WinUI.Core.Services;

namespace OmniDownloader.WinUI.Helpers;

/// <summary>
/// 文件传递执行结果（供 UI 显示轻量状态反馈，不含阻塞弹窗）
/// </summary>
public sealed class FileTransferExecutionResult
{
    public bool Success { get; init; }
    public int CopiedCount { get; init; }
    public int MissingCount { get; init; }
    public string ErrorCode { get; init; } = string.Empty;
    public string ErrorMessage { get; init; } = string.Empty;
}

/// <summary>
/// Windows 原生文件剪贴板（CF_HDROP + Preferred DropEffect）与 OLE 跨应用文件拖拽服务：
/// 参考 Windows 资源管理器与 ShareX 实现，仅传递本地文件路径引用，严禁将音视频内容读入内存；
/// 剪贴板使用操作系统接管的 HGLOBAL 句柄，应用退出后引用依然保持有效。
/// </summary>
public static class NativeFileTransferService
{
    private const uint GmemMoveable = 0x0002;
    private const uint GmemZeroinit = 0x0040;
    private const uint GmemAllocFlags = GmemMoveable | GmemZeroinit;
    private const uint CfUnicodeText = 13;

    private const int SOk = 0;
    private const int SFalse = 1;
    private const int ENotImpl = unchecked((int)0x80004001);
    private const int DvEFormatEtc = unchecked((int)0x80040064);
    private const int OleEAdviseNotSupported = unchecked((int)0x80040003);
    private const int DataSSameFormatEtc = 0x00040130;
    private const int DragDropSDrop = 0x00040100;
    private const int DragDropSCancel = 0x00040101;
    private const int DragDropSUseDefaultCursors = 0x00040102;
    private const uint MkLButton = 0x0001;

    [DllImport("user32.dll", SetLastError = true)]
    private static extern bool OpenClipboard(IntPtr hWndNewOwner);

    [DllImport("user32.dll", SetLastError = true)]
    private static extern bool CloseClipboard();

    [DllImport("user32.dll", SetLastError = true)]
    private static extern bool EmptyClipboard();

    [DllImport("user32.dll", SetLastError = true)]
    private static extern IntPtr SetClipboardData(uint uFormat, IntPtr hMem);

    [DllImport("user32.dll", CharSet = CharSet.Unicode, SetLastError = true)]
    private static extern uint RegisterClipboardFormat(string lpszFormat);

    [DllImport("kernel32.dll", SetLastError = true)]
    private static extern IntPtr GlobalAlloc(uint uFlags, UIntPtr dwBytes);

    [DllImport("kernel32.dll", SetLastError = true)]
    private static extern IntPtr GlobalLock(IntPtr hMem);

    [DllImport("kernel32.dll", SetLastError = true)]
    [return: MarshalAs(UnmanagedType.Bool)]
    private static extern bool GlobalUnlock(IntPtr hMem);

    [DllImport("kernel32.dll", SetLastError = true)]
    private static extern IntPtr GlobalFree(IntPtr hMem);

    [DllImport("user32.dll")]
    public static extern int GetSystemMetrics(int nIndex);

    [DllImport("ole32.dll")]
    private static extern int OleInitialize(IntPtr pvReserved);

    [DllImport("ole32.dll")]
    private static extern void OleUninitialize();

    [DllImport("ole32.dll")]
    private static extern int DoDragDrop(
        System.Runtime.InteropServices.ComTypes.IDataObject pDataObj,
        IOleDropSource pDropSource,
        uint dwOKEffects,
        out uint pdwEffect);

    /// <summary>
    /// 将一个或多个已下载任务对应的本地文件以 CF_HDROP + DROPEFFECT_COPY 写入系统剪贴板
    /// </summary>
    public static FileTransferExecutionResult CopyTaskFilesToClipboard(IEnumerable<DownloadTask?>? tasks, IntPtr ownerHwnd = default)
    {
        var resolved = FileTransferPathHelper.ResolveExistingLocalFiles(tasks);
        return CopyPathsToClipboardCore(resolved, ownerHwnd);
    }

    /// <summary>
    /// 将一个或多个本地路径以 CF_HDROP + DROPEFFECT_COPY 写入系统剪贴板
    /// </summary>
    public static FileTransferExecutionResult CopyFilesToClipboard(IEnumerable<string?>? rawPaths, IntPtr ownerHwnd = default)
    {
        var resolved = FileTransferPathHelper.ResolveExistingLocalFiles(rawPaths);
        return CopyPathsToClipboardCore(resolved, ownerHwnd);
    }

    private static FileTransferExecutionResult CopyPathsToClipboardCore(FileTransferResolutionResult resolved, IntPtr ownerHwnd)
    {
        if (!resolved.HasValidFiles)
        {
            return new FileTransferExecutionResult
            {
                Success = false,
                MissingCount = resolved.MissingCount,
                ErrorCode = "FILE_NOT_FOUND",
                ErrorMessage = "本地文件不存在或已被移动"
            };
        }

        IntPtr hDrop = IntPtr.Zero;
        IntPtr hEffect = IntPtr.Zero;
        var clipboardOpened = false;

        try
        {
            var dropPayload = FileTransferPathHelper.BuildDropFilesPayload(resolved.ValidPaths);
            hDrop = AllocMovableHGlobal(dropPayload);

            var effectBytes = BitConverter.GetBytes(FileTransferPathHelper.DropEffectCopy);
            hEffect = AllocMovableHGlobal(effectBytes);

            var cfEffect = RegisterClipboardFormat(FileTransferPathHelper.PreferredDropEffectFormatName);

            clipboardOpened = TryOpenClipboardWithRetry(ownerHwnd);
            if (!clipboardOpened)
            {
                return new FileTransferExecutionResult
                {
                    Success = false,
                    MissingCount = resolved.MissingCount,
                    ErrorCode = "CLIPBOARD_BUSY",
                    ErrorMessage = "剪贴板当前正被其他应用占用，请稍后重试"
                };
            }

            if (!EmptyClipboard())
            {
                throw new Win32Exception(Marshal.GetLastWin32Error());
            }

            if (SetClipboardData(FileTransferPathHelper.CfHdropFormatId, hDrop) == IntPtr.Zero)
            {
                throw new Win32Exception(Marshal.GetLastWin32Error());
            }
            // 成功调用 SetClipboardData 后内存所有权已移交 Windows 剪贴板，禁止再手动释放
            hDrop = IntPtr.Zero;

            if (cfEffect != 0 && SetClipboardData(cfEffect, hEffect) != IntPtr.Zero)
            {
                hEffect = IntPtr.Zero;
            }

            return new FileTransferExecutionResult
            {
                Success = true,
                CopiedCount = resolved.ValidPaths.Count,
                MissingCount = resolved.MissingCount
            };
        }
        catch (Exception ex)
        {
            return new FileTransferExecutionResult
            {
                Success = false,
                MissingCount = resolved.MissingCount,
                ErrorCode = "CLIPBOARD_FAILED",
                ErrorMessage = ex.Message
            };
        }
        finally
        {
            if (clipboardOpened)
            {
                CloseClipboard();
            }
            if (hDrop != IntPtr.Zero)
            {
                GlobalFree(hDrop);
            }
            if (hEffect != IntPtr.Zero)
            {
                GlobalFree(hEffect);
            }
        }
    }

    /// <summary>
    /// 独立「复制文件路径」接口：将一条或多条本地路径纯文本写入剪贴板，进程退出后依然可用
    /// </summary>
    public static FileTransferExecutionResult CopyTaskFilePathsToClipboard(IEnumerable<DownloadTask?>? tasks, IntPtr ownerHwnd = default)
    {
        var paths = FileTransferPathHelper.CollectRecordedPaths(tasks);
        if (paths.Count == 0)
        {
            return new FileTransferExecutionResult
            {
                Success = false,
                ErrorCode = "PATH_EMPTY",
                ErrorMessage = "未找到有效的本地文件路径"
            };
        }

        var text = FileTransferPathHelper.FormatPathLinesForClipboard(paths);
        var bytes = Encoding.Unicode.GetBytes(text + "\0");
        IntPtr hText = IntPtr.Zero;
        var opened = false;

        try
        {
            hText = AllocMovableHGlobal(bytes);
            opened = TryOpenClipboardWithRetry(ownerHwnd);
            if (!opened)
            {
                return new FileTransferExecutionResult
                {
                    Success = false,
                    ErrorCode = "CLIPBOARD_BUSY",
                    ErrorMessage = "剪贴板当前正被其他应用占用，请稍后重试"
                };
            }

            if (!EmptyClipboard())
            {
                throw new Win32Exception(Marshal.GetLastWin32Error());
            }
            if (SetClipboardData(CfUnicodeText, hText) == IntPtr.Zero)
            {
                throw new Win32Exception(Marshal.GetLastWin32Error());
            }
            hText = IntPtr.Zero;

            return new FileTransferExecutionResult
            {
                Success = true,
                CopiedCount = paths.Count
            };
        }
        catch (Exception ex)
        {
            return new FileTransferExecutionResult
            {
                Success = false,
                ErrorCode = "CLIPBOARD_FAILED",
                ErrorMessage = ex.Message
            };
        }
        finally
        {
            if (opened) CloseClipboard();
            if (hText != IntPtr.Zero) GlobalFree(hText);
        }
    }

    /// <summary>
    /// 启动原生 OLE 跨应用文件拖拽 (DoDragDrop + IDataObject CF_HDROP)，仅传递本地文件引用
    /// </summary>
    public static FileTransferExecutionResult BeginOleFileDragDrop(IEnumerable<DownloadTask?>? tasks)
    {
        var resolved = FileTransferPathHelper.ResolveExistingLocalFiles(tasks);
        return BeginOleFileDragDropCore(resolved);
    }

    /// <summary>
    /// 基于指定路径列表启动原生 OLE 跨应用文件拖拽
    /// </summary>
    public static FileTransferExecutionResult BeginOleFileDragDrop(IEnumerable<string?>? rawPaths)
    {
        var resolved = FileTransferPathHelper.ResolveExistingLocalFiles(rawPaths);
        return BeginOleFileDragDropCore(resolved);
    }

    /// <summary>
    /// 创建可供内部及单元测试直接校验的 OLE 文件 IDataObject 实例
    /// </summary>
    internal static System.Runtime.InteropServices.ComTypes.IDataObject CreateOleDataObjectForTesting(IReadOnlyList<string> filePaths)
    {
        var payload = FileTransferPathHelper.BuildDropFilesPayload(filePaths);
        var cfEffect = RegisterClipboardFormat(FileTransferPathHelper.PreferredDropEffectFormatName);
        return new OleFileDataObject(payload, cfEffect);
    }

    private static FileTransferExecutionResult BeginOleFileDragDropCore(FileTransferResolutionResult resolved)
    {
        if (!resolved.HasValidFiles)
        {
            return new FileTransferExecutionResult
            {
                Success = false,
                MissingCount = resolved.MissingCount,
                ErrorCode = "FILE_NOT_FOUND",
                ErrorMessage = "本地文件不存在或已被移动"
            };
        }

        var oleInitHr = OleInitialize(IntPtr.Zero);
        var mustUninit = oleInitHr == SOk || oleInitHr == SFalse;
        try
        {
            var payload = FileTransferPathHelper.BuildDropFilesPayload(resolved.ValidPaths);
            var cfEffect = RegisterClipboardFormat(FileTransferPathHelper.PreferredDropEffectFormatName);
            var dataObject = new OleFileDataObject(payload, cfEffect);
            var dropSource = new OleFileDropSource();

            var hr = DoDragDrop(dataObject, dropSource, FileTransferPathHelper.DropEffectCopy, out _);
            var ok = hr == DragDropSDrop || hr == DragDropSCancel || hr == SOk;
            return new FileTransferExecutionResult
            {
                Success = ok,
                CopiedCount = hr == DragDropSDrop ? resolved.ValidPaths.Count : 0,
                MissingCount = resolved.MissingCount,
                ErrorCode = ok ? string.Empty : $"OLE_0x{hr:X8}"
            };
        }
        catch (Exception ex)
        {
            return new FileTransferExecutionResult
            {
                Success = false,
                MissingCount = resolved.MissingCount,
                ErrorCode = "DRAG_FAILED",
                ErrorMessage = ex.Message
            };
        }
        finally
        {
            if (mustUninit)
            {
                OleUninitialize();
            }
        }
    }

    private static bool TryOpenClipboardWithRetry(IntPtr ownerHwnd)
    {
        for (var i = 0; i < 10; i++)
        {
            if (OpenClipboard(ownerHwnd))
            {
                return true;
            }
            if (ownerHwnd != IntPtr.Zero && OpenClipboard(IntPtr.Zero))
            {
                return true;
            }
            Thread.Sleep(15);
        }
        return false;
    }

    private static IntPtr AllocMovableHGlobal(byte[] data)
    {
        var hMem = GlobalAlloc(GmemAllocFlags, (UIntPtr)data.Length);
        if (hMem == IntPtr.Zero)
        {
            throw new Win32Exception(Marshal.GetLastWin32Error());
        }

        var ptr = GlobalLock(hMem);
        if (ptr == IntPtr.Zero)
        {
            var err = Marshal.GetLastWin32Error();
            GlobalFree(hMem);
            throw new Win32Exception(err);
        }

        try
        {
            Marshal.Copy(data, 0, ptr, data.Length);
        }
        finally
        {
            GlobalUnlock(hMem);
        }

        return hMem;
    }

    [ComImport]
    [Guid("00000121-0000-0000-C000-000000000046")]
    [InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
    private interface IOleDropSource
    {
        [PreserveSig]
        int QueryContinueDrag([MarshalAs(UnmanagedType.Bool)] bool fEscapePressed, uint grfKeyState);

        [PreserveSig]
        int GiveFeedback(uint dwEffect);
    }

    [ComVisible(true)]
    private sealed class OleFileDropSource : IOleDropSource
    {
        public int QueryContinueDrag(bool fEscapePressed, uint grfKeyState)
        {
            if (fEscapePressed) return DragDropSCancel;
            if ((grfKeyState & MkLButton) == 0) return DragDropSDrop;
            return SOk;
        }

        public int GiveFeedback(uint dwEffect) => DragDropSUseDefaultCursors;
    }

    [ComVisible(true)]
    private sealed class OleFileDataObject : System.Runtime.InteropServices.ComTypes.IDataObject
    {
        private readonly byte[] _dropFilesPayload;
        private readonly short _cfHdrop = unchecked((short)FileTransferPathHelper.CfHdropFormatId);
        private readonly short _cfPreferredEffect;
        private readonly bool _hasEffectFormat;
        private readonly FORMATETC[] _formats;

        public OleFileDataObject(byte[] dropFilesPayload, uint preferredEffectFormat)
        {
            _dropFilesPayload = dropFilesPayload;
            _hasEffectFormat = preferredEffectFormat != 0;
            _cfPreferredEffect = unchecked((short)preferredEffectFormat);

            var list = new List<FORMATETC>(2)
            {
                new()
                {
                    cfFormat = _cfHdrop,
                    dwAspect = DVASPECT.DVASPECT_CONTENT,
                    lindex = -1,
                    tymed = TYMED.TYMED_HGLOBAL
                }
            };
            if (_hasEffectFormat)
            {
                list.Add(new FORMATETC
                {
                    cfFormat = _cfPreferredEffect,
                    dwAspect = DVASPECT.DVASPECT_CONTENT,
                    lindex = -1,
                    tymed = TYMED.TYMED_HGLOBAL
                });
            }
            _formats = list.ToArray();
        }

        private bool IsHdrop(ref FORMATETC format) =>
            format.cfFormat == _cfHdrop &&
            (format.dwAspect == DVASPECT.DVASPECT_CONTENT || format.dwAspect == 0) &&
            format.tymed.HasFlag(TYMED.TYMED_HGLOBAL);

        private bool IsDropEffect(ref FORMATETC format) =>
            _hasEffectFormat &&
            format.cfFormat == _cfPreferredEffect &&
            (format.dwAspect == DVASPECT.DVASPECT_CONTENT || format.dwAspect == 0) &&
            format.tymed.HasFlag(TYMED.TYMED_HGLOBAL);

        public void GetData(ref FORMATETC format, out STGMEDIUM medium)
        {
            if (IsHdrop(ref format))
            {
                medium = new STGMEDIUM
                {
                    tymed = TYMED.TYMED_HGLOBAL,
                    unionmember = AllocMovableHGlobal(_dropFilesPayload),
                    pUnkForRelease = null
                };
                return;
            }

            if (IsDropEffect(ref format))
            {
                medium = new STGMEDIUM
                {
                    tymed = TYMED.TYMED_HGLOBAL,
                    unionmember = AllocMovableHGlobal(BitConverter.GetBytes(FileTransferPathHelper.DropEffectCopy)),
                    pUnkForRelease = null
                };
                return;
            }

            throw new COMException("Unsupported FORMATETC", DvEFormatEtc);
        }

        public void GetDataHere(ref FORMATETC format, ref STGMEDIUM medium) =>
            throw new COMException("GetDataHere not supported", ENotImpl);

        public int QueryGetData(ref FORMATETC format) =>
            (IsHdrop(ref format) || IsDropEffect(ref format)) ? SOk : DvEFormatEtc;

        public int GetCanonicalFormatEtc(ref FORMATETC formatIn, out FORMATETC formatOut)
        {
            formatOut = formatIn;
            return DataSSameFormatEtc;
        }

        public void SetData(ref FORMATETC formatIn, ref STGMEDIUM medium, bool release) =>
            throw new COMException("SetData not supported", ENotImpl);

        public IEnumFORMATETC EnumFormatEtc(DATADIR direction)
        {
            if (direction == DATADIR.DATADIR_GET)
            {
                return new OleEnumFormatEtc(_formats, 0);
            }
            throw new COMException("DATADIR_SET not supported", ENotImpl);
        }

        public int DAdvise(ref FORMATETC pFormatetc, ADVF advf, IAdviseSink adviseSink, out int connection)
        {
            connection = 0;
            return OleEAdviseNotSupported;
        }

        public void DUnadvise(int connection) =>
            throw new COMException("DUnadvise not supported", OleEAdviseNotSupported);

        public int EnumDAdvise(out IEnumSTATDATA enumAdvise)
        {
            enumAdvise = null!;
            return OleEAdviseNotSupported;
        }
    }

    [ComVisible(true)]
    private sealed class OleEnumFormatEtc : IEnumFORMATETC
    {
        private readonly FORMATETC[] _formats;
        private int _index;

        public OleEnumFormatEtc(FORMATETC[] formats, int index)
        {
            _formats = formats;
            _index = index;
        }

        public int Next(int celt, FORMATETC[] rgelt, int[]? pceltFetched)
        {
            if (rgelt is null || celt <= 0) return SFalse;
            var fetched = 0;
            while (_index < _formats.Length && fetched < celt)
            {
                rgelt[fetched] = _formats[_index];
                fetched++;
                _index++;
            }
            if (pceltFetched is { Length: > 0 })
            {
                pceltFetched[0] = fetched;
            }
            return fetched == celt ? SOk : SFalse;
        }

        public int Skip(int celt)
        {
            _index = Math.Min(_formats.Length, Math.Max(0, _index + celt));
            return _index < _formats.Length ? SOk : SFalse;
        }

        public int Reset()
        {
            _index = 0;
            return SOk;
        }

        public void Clone(out IEnumFORMATETC newEnum) =>
            newEnum = new OleEnumFormatEtc(_formats, _index);
    }
}
