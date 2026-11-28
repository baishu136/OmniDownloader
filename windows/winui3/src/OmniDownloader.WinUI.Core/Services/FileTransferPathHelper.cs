using System.Text;
using OmniDownloader.WinUI.Core.Models;

namespace OmniDownloader.WinUI.Core.Services;

/// <summary>
/// 文件传递解析结果（用于剪贴板文件引用复制与原生 OLE 跨应用拖拽前的路径校验与去重）
/// </summary>
public sealed class FileTransferResolutionResult
{
    public IReadOnlyList<string> ValidPaths { get; }
    public int MissingCount { get; }
    public int DuplicateSkippedCount { get; }

    public bool HasValidFiles => ValidPaths.Count > 0;

    public FileTransferResolutionResult(
        IReadOnlyList<string> validPaths,
        int missingCount,
        int duplicateSkippedCount)
    {
        ValidPaths = validPaths;
        MissingCount = missingCount;
        DuplicateSkippedCount = duplicateSkippedCount;
    }
}

/// <summary>
/// 纯托管零文件读取的文件引用路径解析与 CF_HDROP (DROPFILES) 载荷构造器：
/// 严格仅操作文件路径字符串，不读取目标媒体文件内容，内存开销仅取决于路径数量与字符长度。
/// </summary>
public static class FileTransferPathHelper
{
    public const uint CfHdropFormatId = 15;
    public const string PreferredDropEffectFormatName = "Preferred DropEffect";
    public const uint DropEffectCopy = 1;

    /// <summary>
    /// 从下载任务集合中提取已完成且实际存在于磁盘上的本地文件路径（不重复、按原顺序保留）
    /// </summary>
    public static FileTransferResolutionResult ResolveExistingLocalFiles(IEnumerable<DownloadTask?>? tasks)
    {
        if (tasks is null)
        {
            return new FileTransferResolutionResult(Array.Empty<string>(), 0, 0);
        }

        return ResolveExistingLocalFiles(tasks.Select(t => t?.LocalFilePath));
    }

    /// <summary>
    /// 校验并归一化路径列表，过滤不存在或无效路径并按 Windows 大小写不敏感规则去重
    /// </summary>
    public static FileTransferResolutionResult ResolveExistingLocalFiles(IEnumerable<string?>? rawPaths)
    {
        if (rawPaths is null)
        {
            return new FileTransferResolutionResult(Array.Empty<string>(), 0, 0);
        }

        var valid = new List<string>();
        var seen = new HashSet<string>(StringComparer.OrdinalIgnoreCase);
        var missing = 0;
        var dupes = 0;

        foreach (var raw in rawPaths)
        {
            if (string.IsNullOrWhiteSpace(raw))
            {
                missing++;
                continue;
            }

            string fullPath;
            try
            {
                fullPath = Path.GetFullPath(raw.Trim());
            }
            catch
            {
                missing++;
                continue;
            }

            if (!File.Exists(fullPath))
            {
                missing++;
                continue;
            }

            if (!seen.Add(fullPath))
            {
                dupes++;
                continue;
            }

            valid.Add(fullPath);
        }

        return new FileTransferResolutionResult(valid, missing, dupes);
    }

    /// <summary>
    /// 提取用于「复制文件路径」的去重路径列表（优先返回实际存在的文件，若全部已移动仍尽量返回原记录路径）
    /// </summary>
    public static IReadOnlyList<string> CollectRecordedPaths(IEnumerable<DownloadTask?>? tasks)
    {
        if (tasks is null) return Array.Empty<string>();
        var list = new List<string>();
        var seen = new HashSet<string>(StringComparer.OrdinalIgnoreCase);

        foreach (var task in tasks)
        {
            var raw = task?.LocalFilePath;
            if (string.IsNullOrWhiteSpace(raw)) continue;

            var trimmed = raw.Trim();
            string normalized;
            try
            {
                normalized = Path.GetFullPath(trimmed);
            }
            catch
            {
                normalized = trimmed;
            }

            if (seen.Add(normalized))
            {
                list.Add(normalized);
            }
        }

        return list;
    }

    /// <summary>
    /// 将多条本地路径格式化为换行分隔的纯文本，仅供独立「复制文件路径」功能使用
    /// </summary>
    public static string FormatPathLinesForClipboard(IReadOnlyList<string> filePaths)
    {
        if (filePaths is null || filePaths.Count == 0) return string.Empty;
        return string.Join(Environment.NewLine, filePaths.Where(p => !string.IsNullOrWhiteSpace(p)));
    }

    /// <summary>
    /// 构造 Windows 原生 CF_HDROP 内存载荷：20 字节 DROPFILES 头 (pFiles=20, fWide=1) + UTF-16LE 路径列表 + 双空终止符
    /// </summary>
    public static byte[] BuildDropFilesPayload(IReadOnlyList<string> filePaths)
    {
        if (filePaths is null || filePaths.Count == 0)
        {
            throw new ArgumentException("文件路径列表不能为空", nameof(filePaths));
        }

        const int headerSize = 20; // sizeof(DROPFILES): DWORD pFiles(4) + POINT(8) + BOOL fNC(4) + BOOL fWide(4)
        var totalChars = 1; // 末尾额外 1 个 UTF-16 '\0' 终止整个列表
        foreach (var p in filePaths)
        {
            if (string.IsNullOrWhiteSpace(p))
            {
                throw new ArgumentException("文件路径列表不能包含空路径", nameof(filePaths));
            }
            totalChars += p.Length + 1;
        }

        var buffer = new byte[headerSize + totalChars * 2];
        // pFiles = 20 (小端序)
        BitConverter.TryWriteBytes(buffer.AsSpan(0, 4), (uint)headerSize);
        // fWide = 1 (Unicode UTF-16LE，偏移 16)
        BitConverter.TryWriteBytes(buffer.AsSpan(16, 4), 1u);

        var offset = headerSize;
        foreach (var p in filePaths)
        {
            var byteCount = Encoding.Unicode.GetBytes(p, buffer.AsSpan(offset));
            offset += byteCount;
            // Encoding.Unicode 之后保留 2 字节 0x0000 作为单条路径终止符
            offset += 2;
        }

        return buffer;
    }
}
