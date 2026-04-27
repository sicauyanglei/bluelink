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
    public const byte CMD_NAVIGATE_REQUEST = 0x09; // Navigate to subdirectory: folder name length(4 bytes) + folder name
    public const byte CMD_BACK_REQUEST = 0x0A;     // Go back to parent directory
    public const byte CMD_CREATE_FOLDER_REQUEST = 0x0B; // Create folder: folder name length(4 bytes) + folder name
    public const byte CMD_SHARE_PATH_CHANGED = 0x0C; // Share path changed notification (PC -> Android)
    public const byte CMD_UPLOAD_CHUNK = 0x0D;       // Upload chunk (streaming mode data)
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
        packet[1] = (byte)(data.Length >> 24);
        packet[2] = (byte)(data.Length >> 16);
        packet[3] = (byte)(data.Length >> 8);
        packet[4] = (byte)data.Length;
        data.CopyTo(packet, HEADER_SIZE);
        return packet;
    }

    public static (byte command, byte[] data) ParsePacket(byte[] packet)
    {
        var command = packet[0];
        var length = ReadInt32BigEndian(packet, 1);
        var data = new byte[length];
        Array.Copy(packet, HEADER_SIZE, data, 0, length);
        return (command, data);
    }

    // Big-endian read helpers (for matching Android/Java standard)
    public static int ReadInt32BigEndian(byte[] buffer, int offset)
    {
        return (buffer[offset] << 24) | (buffer[offset + 1] << 16) |
               (buffer[offset + 2] << 8) | buffer[offset + 3];
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
}
