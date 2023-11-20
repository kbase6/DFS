import java.net.*;
import java.io.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class FileServer {
    private ServerSocket serverSocket;
    private LockManager lockManager;

    public FileServer(int port) throws IOException {
      serverSocket = new ServerSocket(port);
      lockManager = new LockManager();
    }

    public void start() throws IOException {
      while (true) {
        Socket clientSocket = serverSocket.accept();
        new ClientHandler(clientSocket, lockManager).start();
      }
    }

    public static void main(String[] args) throws IOException {
      int port = 6666;
      FileServer server = new FileServer(port);
      System.out.println("Server started on port " + port);
      server.start();
    }
}

// ここにClientHandlerクラスを実装
class ClientHandler extends Thread {
    private Socket clientSocket;
    private PrintWriter out;
    private BufferedReader in;
    private Map<String, RandomAccessFile> openFiles;
    private Map<String, String> filePermissions;
    private LockManager lockManager;

    public ClientHandler(Socket socket, LockManager lockManager) {
        this.clientSocket = socket;
        this.openFiles = new HashMap<>();
        this.filePermissions = new HashMap<>();
        this.lockManager = lockManager;
    }

    public void run() {
        try {
            out = new PrintWriter(clientSocket.getOutputStream(), true);
            in = new BufferedReader(new InputStreamReader(clientSocket.getInputStream()));

            String inputLine;
            while ((inputLine = in.readLine()) != null) {
                String[] commands = inputLine.split(" ", 3);
                String command = commands[0];

                switch (command) {
                    case "OPEN":
                        handleOpen(commands[1], commands[2]);
                        break;
                    case "WRITE":
                        if (commands.length > 1) {
                            String fileName = commands[1];
                            StringBuilder fileDataBuilder = new StringBuilder();
                            while (!(inputLine = in.readLine()).equals("<END_OF_DATA>")) {
                                fileDataBuilder.append(inputLine).append("\n");
                            }
                            String fileData = fileDataBuilder.toString();
                            System.out.println(fileData); // Debugging
                            handleWrite(fileName, fileData);
                        } else {
                            out.println("Error: Missing file name for WRITE command");
                        }
                        break;
                    case "CLOSE":
                        handleClose(commands[1]);
                        break;
                    default:
                        out.println("Invalid Command");
                        break;
                }
            }

            in.close();
            out.close();
            clientSocket.close();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private void handleOpen(String fileName, String permission) throws IOException {
        if ("w".equals(permission) || "rw".equals(permission)) {
            if (!lockManager.tryLock(fileName)) {
                out.println("Write access denied: File is currently open with write permission by other user");
                return;
            }
        }

        String mode = "r".equals(permission) ? "r" : "rw";
        RandomAccessFile file = new RandomAccessFile(fileName, mode);
        openFiles.put(fileName, file);
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        byte[] data = new byte[1024];
        int bytesRead;

        while ((bytesRead = file.read(data, 0, data.length)) != -1) {
            buffer.write(data, 0, bytesRead);
        }

        buffer.flush();
        String fileContent = buffer.toString();
        out.println(fileContent);
        out.println("File opened with " + permission + " permission: " + fileName);
    }

    private void handleWrite(String fileName, String fileData) {
        RandomAccessFile file = openFiles.get(fileName);
        if (file != null) {
            try {
                file.setLength(0); // Clear the file before writing
                file.write(fileData.getBytes());
                out.println("Data written to file: " + fileName);
            } catch (IOException e) {
                out.println("Error writing to file: " + e.getMessage());
            }
        } else {
            out.println("File not open");
        }
    }

    private void handleClose(String fileName) throws IOException {
        RandomAccessFile file = openFiles.get(fileName);
        if (file != null) {
            file.close();
            openFiles.remove(fileName);
            filePermissions.remove(fileName);
            lockManager.unlock(fileName);
            out.println("File closed: " + fileName);
        } else {
            out.println("File not open");
        }
    }
}

class LockManager {
    private Set<String> lockedFiles = ConcurrentHashMap.newKeySet();

    public synchronized boolean tryLock(String fileName) {
        return lockedFiles.add(fileName);
    }

    public synchronized void unlock(String fileName) {
        lockedFiles.remove(fileName);
    }

    public boolean isLocked(String fileName) {
        return lockedFiles.contains(fileName);
    }
}
