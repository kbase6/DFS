import java.net.*;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;

public class FileServerA implements AutoCloseable {
    private ServerSocket serverSocket;
    private ExecutorService executorService;
    private LockManager lockManager;

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
    private Socket clientSocket;
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
                String[] commands = inputLine.split(" ", 3);
                String command = commands[0];
                
                switch (command) {
                    case "OPEN":
                        handleOpen(commands);
                        break;
                    case "WRITE":
                        handleWrite(commands);
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

    private void handleOpen(String[] commands) throws IOException {
        String fileName = commands[1];
        String permission = commands[2];

        if ("w".equals(permission) || "rw".equals(permission)) {
            if (!lockManager.tryLock(fileName)) {
                out.println("Write access denied: File is currently open with write permission by other user");
                return;
            }
        }
        System.out.println("Request: " + commands[0] + " " + fileName + " " + permission); // Debugging
        RandomAccessFile file = new RandomAccessFile(fileName, permission);
        // openFiles.put(fileName, file); // delete
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        byte[] data = new byte[1024];
        int bytesRead;

        while ((bytesRead = file.read(data, 0, data.length)) != -1) {
            buffer.write(data, 0, bytesRead);
        }

        buffer.flush();
        String fileData = buffer.toString();
        out.println(fileData);
        out.println("END_OF_DATA");
        System.out.println("DONE");
        file.close();
    }

    private void handleWrite(String[] commands) throws IOException {
        String fileName = commands[1];
        System.out.println("Request: " + commands[0] + " " + fileName); // Debugging

        StringBuilder fileContent = new StringBuilder();
        String line;
        while (!(line = in.readLine()).equals("END_OF_DATA")) {
            fileContent.append(line).append("\n");
        }

        // Remove the last newline character added
        if (fileContent.length() > 0) {
            fileContent.deleteCharAt(fileContent.length() - 1);
        }

        String fileData = fileContent.toString();

        RandomAccessFile file = new RandomAccessFile(fileName, "rw");

        System.out.println("File opened. Updating file.");
        try {
            file.setLength(0); // Clear file before writing
            file.write(fileData.getBytes());
            out.println("Data written to file: " + fileName);
            System.out.println(fileName + " updated.");
        } catch (IOException e) {
            out.println("Error writing to file: " + e.getMessage());
        }
        System.out.println("DONE");
        file.close();
    }

    private void closeResources() {
        try {
            if (in != null) in.close();
            if (out != null) out.close();
            if (clientSocket != null) clientSocket.close();
        } catch (IOException e) {
            e.printStackTrace();
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
}