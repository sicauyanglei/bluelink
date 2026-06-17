using System.Buffers.Binary;

namespace BluetoothFileServer.Protocols;

public static class FileTransferProtocol
{
    // Command codes
    public const byte CMD_LIST_REQUEST = 0x01;      // Request file list
    public const byte CMD_LIST_RESPONSE = 0x02;     // Response file list
    public const byte CMD_DOWNLOAD_REQUEST = 0x03;  // Download request: filename(4 bytes length) + filename + offset(8 bytes)
    public const byte CMD_DOWNLOAD_RESPONSE = 0x04; // Download response (data)
    public const byte CMD_UPLOAD_REQUEST = 0x05;    // Upload request: filename(4 bytes length) + filename + offset(8 bytes) + data
    public const byte CMD_UPLOAD_RESPONSE = 0x06;   // Upload response
    public const byte CMD_DELETE_REQUEST = 0x07;    // Delete request
    public const byte CMD_TRANSFER_COMPLETE = 0x08; // Transfer complete signal
    public const byte CMD_NAVIGATE_REQUEST = 0x09;  // Navigate to subdirectory
    public const byte CMD_BACK_REQUEST = 0x0A;      // Go back to parent directory
    public const byte CMD_CREATE_FOLDER_REQUEST = 0x0B; // Create folder
    public const byte CMD_SHARE_PATH_CHANGED = 0x0C; // Share path changed notification (PC -> Android)
    public const byte CMD_UPLOAD_CHUNK = 0x0D;       // Upload chunk (streaming mode data)
    public const byte CMD_HEARTBEAT = 0x0E;          // P1-11: 心跳命令（显式处理）
    public const byte CMD_FILE_HASH = 0x0F;          // P3-4: 请求文件 SHA-256 哈希
    public const byte CMD_SUCCESS = 0xFE;           // Success
    public const byte CMD_ERROR = 0xFF;             // Error

    // Protocol header: command(1 byte) + length(4 bytes) + data(N bytes)
    public const int HEADER_SIZE = 5;

    public static byte[] CreatePacket(byte command, byte[]? data = null)
    {
        data ??= Array.Empty<byte>();
        var packet = new byte[HEADER_SIZE + data.Length];
        packet[0] = command;
        // Use big-endian (network byte order) for length
        WriteInt32BigEndian(packet, 1, data.Length);
        data.CopyTo(packet, HEADER_SIZE);
        return packet;
    }

    /// <summary>
    /// P2-1: 零拷贝重载，避免每次 Array.Copy 创建新数组
    /// </summary>
    public static byte[] CreatePacket(byte command, byte[] buffer, int length)
    {
        var packet = new byte[HEADER_SIZE + length];
        packet[0] = command;
        WriteInt32BigEndian(packet, 1, length);
        System.Array.Copy(buffer, 0, packet, HEADER_SIZE, length);
        return packet;
    }

    public static (byte command, byte[] data) ParsePacket(byte[] packet)
    {
        if (packet == null || packet.Length < HEADER_SIZE)
            throw new ArgumentException("packet too short");

        var command = packet[0];
        var length = ReadInt32BigEndian(packet, 1);

        // 防御：长度越界
        if (length < 0 || length > packet.Length - HEADER_SIZE)
            throw new ArgumentException($"invalid length {length}");

        var data = new byte[length];
        Array.Copy(packet, HEADER_SIZE, data, 0, length);
        return (command, data);
    }

    // 修复：用 uint 解析避免最高位为 1 时变负数（长度 > 2GB）
    public static int ReadInt32BigEndian(byte[] buffer, int offset)
    {
        return (int)((uint)buffer[offset] << 24 | (uint)buffer[offset + 1] << 16 |
                     (uint)buffer[offset + 2] << 8 | buffer[offset + 3]);
    }

    public static long ReadInt64BigEndian(byte[] buffer, int offset)
    {
        return ((long)buffer[offset] << 56) | ((long)buffer[offset + 1] << 48) |
               ((long)buffer[offset + 2] << 40) | ((long)buffer[offset + 3] << 32) |
               ((long)buffer[offset + 4] << 24) | ((long)buffer[offset + 5] << 16) |
               ((long)buffer[offset + 6] << 8) | buffer[offset + 7];
    }

    public static void WriteInt32BigEndian(byte[] buffer, int offset, int value)
    {
        buffer[offset] = (byte)(value >> 24);
        buffer[offset + 1] = (byte)(value >> 16);
        buffer[offset + 2] = (byte)(value >> 8);
        buffer[offset + 3] = (byte)value;
    }

    public static void WriteInt64BigEndian(byte[] buffer, int offset, long value)
    {
        buffer[offset] = (byte)(value >> 56);
        buffer[offset + 1] = (byte)(value >> 48);
        buffer[offset + 2] = (byte)(value >> 40);
        buffer[offset + 3] = (byte)(value >> 32);
        buffer[offset + 4] = (byte)(value >> 24);
        buffer[offset + 5] = (byte)(value >> 16);
        buffer[offset + 6] = (byte)(value >> 8);
        buffer[offset + 7] = (byte)value;
    }

    /// <summary>
    /// P1-10: CRC16-CCITT 校验（用于数据完整性）
    /// </summary>
    public static ushort ComputeCrc16(byte[] data, int offset, int length)
    {
        ushort crc = 0xFFFF;
        for (int i = 0; i < length; i++)
        {
            crc ^= (ushort)(data[offset + i] << 8);
            for (int j = 0; j < 8; j++)
            {
                if ((crc & 0x8000) != 0)
                    crc = (ushort)((crc << 1) ^ 0x1021);
                else
                    crc = (ushort)(crc << 1);
            }
        }
        return crc;
    }
}
