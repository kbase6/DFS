import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.io.RandomAccessFile;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class FileServerA implements AutoCloseable {
    private final ServerSocket serverSocket;
    private final ExecutorService executorService;
    private final LockManager lockManager;

    public FileServerA(int port) throws IOException {
        serverSocket = new ServerSocket(port);
        executorService = Executors.newCachedThreadPool();
        lockManager = new LockManager();
    }

    public void start() {
        try {
            while (!serverSocket.isClosed()) {
                Socket clientSocket = serverSocket.accept();
                executorService.submit(new ClientHandler(clientSocket, lockManager));
            }
        } catch (IOException e) {
            e.printStackTrace();
        } finally {
            executorService.shutdown();
        }
    }

    public static void main(String[] args) {
        int port = 6666;
        try (FileServerA server = new FileServerA(port)) {
            System.out.println("Server started on port " + port);
            server.start();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    @Override
    public void close() throws IOException {
        if (serverSocket != null && !serverSocket.isClosed()) {
            serverSocket.close();
        }
        if (executorService != null && !executorService.isShutdown()) {
            executorService.shutdown();
        }
    }
}

class ClientHandler implements Runnable {
    private final Socket clientSocket;
    private PrintWriter out;
    private BufferedReader in;
    private LockManager lockManager;

    public ClientHandler(Socket socket, LockManager lockManager) {
        this.clientSocket = socket;
        this.lockManager = lockManager;
    }

    @Override
    public void run() {
        try {
            out = new PrintWriter(clientSocket.getOutputStream(), true);
            in = new BufferedReader(new InputStreamReader(clientSocket.getInputStream()));

            String inputLine;
            System.out.println("Ready to accept commands.");
            while ((inputLine = in.readLine()) != null) {
                String[] commands = inputLine.split(" ", 6);
                String command = commands[0];

                switch (command) {
                    case "LS":
                        handleLs(commands.length > 1 ? commands[1] : ".");
                        break;
                    case "OPEN":
                        handleOpen(commands);
                        break;
                    case "WRITE":
                        handleWrite(commands);
                        break;
                    case "CREATE_FILE":
                        handleCreateFILE(commands);
                        break;
                    case "CREATE_DIR":
                        handleCreateDirectory(commands);
                        break;
                    case "DELETE":
                        handleDelete(commands);
                        break;
                    default:
                        out.println("Invalid command");
                        break;
                }
            }
        } catch (IOException e) {
            e.printStackTrace();
        } finally {
            closeResources();
        }
    }

    private void handleDelete(String[] commands) throws IOException {
        if (commands.length < 2) {
            out.println("Error: No file or directory name specified");
            return;
        }

        String name = commands[1];
        File fileOrDirectory = new File(name);

        if (!fileOrDirectory.exists()) {
            out.println("Error: File or directory does not exist - " + name);
            return;
        }

        boolean deleted = fileOrDirectory.delete();
        if (deleted) {
            out.println("File or directory deleted successfully: " + name);
        } else {
            out.println("Error: Could not delete the file or directory - " + name);
        }
    }

    private void handleCreateDirectory(String[] commands) throws IOException {
        if (commands.length < 2) {
            out.println("Error: No directory name specified");
            return;
        }

        String dirName = commands[1];
        File directory = new File(dirName);

        if (directory.exists()) {
            out.println("Error: Directory already exists - " + dirName);
            return;
        }

        boolean created = directory.mkdir();
        if (created) {
            out.println("Directory created successfully: " + dirName);
        } else {
            out.println("Error: Could not create the directory - " + dirName);
        }
    }

    private void handleCreateFILE(String[] commands) throws IOException {
        if (commands.length < 2) {
            out.println("Error: No file name specified");
            return;
        }

        String fileName = commands[1];
        File file = new File(fileName);

        if (file.exists()) {
            out.println("Error: File already exists - " + fileName);
            return;
        }

        boolean created = file.createNewFile();
        if (created) {
            out.println("File created successfully: " + fileName);
        } else {
            out.println("Error: Could not create the file - " + fileName);
        }
    }

    private void handleLs(String path) {
        if (path == null || path.isEmpty()) {
            path = ".";
        }

        File directory = new File(path);
        if (directory.exists() && directory.isDirectory()) {
            String[] files = directory.list();
            if (files != null) {
                for (String file : files) {
                    out.println(file);
                }
            }
            out.println("END_OF_LS");
        } else {
            out.println("Error: Directory does not exist - " + path);
        }
    }

    private void handleOpen(String[] commands) throws IOException {
        if (commands.length < 3) {
            out.println("Error: Insufficient arguments for OPEN command.");
            return;
        }
        String fileName = commands[1];
        String permission = commands[2];

        // Check write permissions and try to acquire lock if needed.
        if ("w".equals(permission) || "rw".equals(permission)) {
            if (!lockManager.tryLock(fileName)) {
                out.println("Write access denied: File is currently open with write permission by another user.");
                return;
            }
        }

        // Default values for full file reading.
        long startPosition = 0;
        long readLength = Long.MAX_VALUE;

        // Check if startPosition and readLength are provided.
        if (commands.length > 3) {
            try {
                startPosition = Long.parseLong(commands[3]);
                if (commands.length > 4) {
                    readLength = Long.parseLong(commands[4]);
                }
            } catch (NumberFormatException e) {
                out.println("Error: Invalid start position or read length.");
                return;
            }
        }

        try (RandomAccessFile file = new RandomAccessFile(fileName, "r")) {
            long fileLength = file.length();
            if (fileLength == 0) {
                out.println("END_OF_DATA");
                return;
            }

            if (startPosition < 0 || startPosition >= fileLength) {
                out.println("Error: Start position is out of file bounds.");
                return;
            }

            // Adjust readLength if it goes beyond the file's content.
            if (startPosition + readLength > fileLength) {
                readLength = fileLength - startPosition;
            }

            // Move the file pointer to the start position.
            file.seek(startPosition);

            byte[] buffer = new byte[1024];
            ByteArrayOutputStream outputBuffer = new ByteArrayOutputStream();
            long totalBytesRead = 0;

            while (totalBytesRead < readLength) {
                int bytesToRead = (int) Math.min(buffer.length, readLength - totalBytesRead);
                int bytesRead = file.read(buffer, 0, bytesToRead);
                if (bytesRead == -1) {
                    break; // End of file reached.
                }
                outputBuffer.write(buffer, 0, bytesRead);
                totalBytesRead += bytesRead;
            }

            // Send the read data to the client.
            out.println(outputBuffer.toString());
            out.println("END_OF_DATA");
            System.out.println("DONE");

            // Add client to list
            if ("r".equals(permission)) {
                lockManager.addReadClient(fileName, this);
            }
        } catch (FileNotFoundException e) {
            out.println("Error: File " + fileName + " not found.");
        } catch (IOException e) {
            out.println("Error reading file: " + e.getMessage());
        }
    }

    // handle write request
    private void handleWrite(String[] commands) throws IOException {
        String fileName = commands[1];

        StringBuilder fileContent = new StringBuilder();
        String line;
        while (!(line = in.readLine()).equals("END_OF_DATA")) {
            fileContent.append(line).append("\n");
        }
        if (fileContent.length() > 0) {
            fileContent.deleteCharAt(fileContent.length() - 1);
        }
        String newContent = fileContent.toString();

        RandomAccessFile file = new RandomAccessFile(fileName, "rw");
        try {
            file.write(newContent.getBytes()); // overwrite file to new content
            out.println("Data written to file: " + fileName);
        } catch (IOException e) {
            out.println("Error writing to file: " + e.getMessage());
        } finally {
            file.close();
            lockManager.unlock(fileName);
            lockManager.notifyReadClients(fileName);
        }
    }

    public void sendFileUpdate(String fileName) {
        out.println("FILE_UPDATE:" + fileName);
        try {
            String fileContent = Files.readString(Paths.get(fileName));
            out.println(fileContent);
            out.println("END_OF_DATA");
        } catch (IOException e) {
            out.println("Error reading file: " + e.getMessage());
        }
    }

    private void closeResources() {
        try {
            if (in != null)
                in.close();
            if (out != null)
                out.close();
            if (clientSocket != null)
                clientSocket.close();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
}

class LockManager {
    private Set<String> lockedFiles = ConcurrentHashMap.newKeySet();
    private Map<String, List<ClientHandler>> readClients = new ConcurrentHashMap<>();

    public synchronized boolean tryLock(String fileName) {
        return lockedFiles.add(fileName);
    }

    public synchronized void unlock(String fileName) {
        lockedFiles.remove(fileName);
        notifyReadClients(fileName);
    }

    public synchronized void addReadClient(String fileName, ClientHandler client) {
        readClients.computeIfAbsent(fileName, k -> new ArrayList<>()).add(client);
        System.out.println("Added read client for " + fileName);
    }

    public synchronized void removeReadClient(String fileName, ClientHandler client) {
        if (readClients.containsKey(fileName)) {
            readClients.get(fileName).remove(client);
        }
    }

    public void notifyReadClients(String fileName) {
        if (readClients.containsKey(fileName)) {
            System.out.println("Notifying read clients for " + fileName);
            for (ClientHandler client : readClients.get(fileName)) {
                client.sendFileUpdate(fileName);
                removeReadClient(fileName, client);
            }
        }
    }
}