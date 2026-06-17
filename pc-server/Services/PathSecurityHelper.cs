using System.IO;
using System.Linq;

namespace BluetoothFileServer.Services;

/// <summary>
/// 路径安全校验工具：防止路径遍历攻击（如 ../）、绝对路径、盘符注入等
/// </summary>
public static class PathSecurityHelper
{
    /// <summary>
    /// 校验相对路径是否安全（不含 .. / 绝对路径 / 盘符 / 非法字符）
    /// </summary>
    public static bool IsSafeRelativeName(string? name)
    {
        if (string.IsNullOrWhiteSpace(name)) return false;
        if (name.Length > 255) return false;

        // 禁止路径分隔符、通配符、控制字符
        if (name.IndexOfAny(Path.GetInvalidFileNameChars()) >= 0) return false;
        if (name.Contains("..")) return false;
        if (name.Contains(Path.DirectorySeparatorChar)) return false;
        if (name.Contains(Path.AltDirectorySeparatorChar)) return false;
        // 禁止 Windows 盘符（如 C:）
        if (name.Length >= 2 && name[1] == ':') return false;
        // 禁止 UNC 前缀
        if (name.StartsWith(@"\\") || name.StartsWith("//")) return false;
        // 禁止绝对路径起始
        if (name.StartsWith(Path.DirectorySeparatorChar) || name.StartsWith(Path.AltDirectorySeparatorChar)) return false;

        return true;
    }

    /// <summary>
    /// 将相对路径片段安全拼接到根目录下，并校验最终路径必须在 root 下。
    /// 返回 null 表示非法。
    /// </summary>
    public static string? SafeCombine(string root, string relativeName)
    {
        if (!IsSafeRelativeName(relativeName)) return null;

        var rootFull = Path.GetFullPath(root);
        var candidate = Path.GetFullPath(Path.Combine(rootFull, relativeName));

        var rootWithSep = rootFull.EndsWith(Path.DirectorySeparatorChar) ? rootFull : rootFull + Path.DirectorySeparatorChar;
        if (!candidate.StartsWith(rootWithSep, System.StringComparison.OrdinalIgnoreCase))
        {
            return null;
        }
        return candidate;
    }

    /// <summary>
    /// 校验相对子路径（可包含分隔符，如 a/b.txt）是否在 root 下
    /// </summary>
    public static string? SafeCombineSubPath(string root, string relativePath)
    {
        if (string.IsNullOrWhiteSpace(relativePath)) return Path.GetFullPath(root);
        if (relativePath.Contains("..")) return null;
        if (relativePath.Length >= 2 && relativePath[1] == ':') return null;
        if (relativePath.StartsWith(@"\\") || relativePath.StartsWith("//")) return null;

        try
        {
            var rootFull = Path.GetFullPath(root);
            var candidate = Path.GetFullPath(Path.Combine(rootFull, relativePath));
            var rootWithSep = rootFull.EndsWith(Path.DirectorySeparatorChar) ? rootFull : rootFull + Path.DirectorySeparatorChar;
            if (!candidate.StartsWith(rootWithSep, System.StringComparison.OrdinalIgnoreCase))
            {
                return null;
            }
            return candidate;
        }
        catch
        {
            return null;
        }
    }
}
