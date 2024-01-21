import java.io.BufferedReader;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.Socket;
import java.util.HashMap;
import java.util.Map;
import java.util.Scanner;
import java.util.concurrent.ConcurrentHashMap;

public class FileClient {
    private final Map<String, String> fileData;
    private final Map<String, String> filePermissions;
    private final Map<Integer, ConnectionResources> connections;
    private final Map<String, Integer> serverPortMap;
    private final ConcurrentHashMap<String, Thread> threadMap = new ConcurrentHashMap<>();

    private class ConnectionResources {
        Socket socket;
        PrintWriter out;
        BufferedReader in;

        ConnectionResources(Socket socket, PrintWriter out, BufferedReader in) {
            this.socket = socket;
            this.out = out;
            this.in = in;
        }
    }

    public FileClient() {
        connections = new HashMap<>(); // store port number and sockets
        serverPortMap = new HashMap<>(); // Initialize the server-port map
        this.fileData = new HashMap<>(); // store file data
        this.filePermissions = new HashMap<>(); // store permission for each file
    }

    public void connectToServers(String serverListFilePath) {
        try (Scanner scanner = new Scanner(new File(serverListFilePath))) {
            while (scanner.hasNextLine()) {
                String line = scanner.nextLine();
                String[] parts = line.split(" ");
                if (parts.length == 2) {
                    String serverName = parts[1];
                    int port = Integer.parseInt(parts[0]);
                    String ip = "127.0.0.1";
                    serverPortMap.put(serverName, port); // Store server and port information
                    startConnection(ip, port, serverName);
                }
            }
        } catch (FileNotFoundException e) {
            System.out.println("Server list file not found: " + e.getMessage());
        }
    }

    public void startConnection(String ip, int port, String serverName) {
        try {
            Socket socket = new Socket(ip, port);
            PrintWriter out = new PrintWriter(socket.getOutputStream(), true);
            BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream()));
            connections.put(port, new ConnectionResources(socket, out, in));
            System.out.println("Successfully started connection with server: " + serverName);
        } catch (IOException e) {
            System.out.println("Error starting connection: " + e.getMessage());
        }
    }

    public void stopConnection() {
        for (int port : connections.keySet()) {
            ConnectionResources resources = connections.get(port);
            if (resources != null) {
                try {
                    if (resources.in != null)
                        resources.in.close();
                    if (resources.out != null)
                        resources.out.close();
                    if (resources.socket != null)
                        resources.socket.close();
                    connections.remove(port);
                } catch (IOException e) {
                    System.out.println("Error stopping connection on port " + port + ": " + e.getMessage());
                }
            }
        }
    }

    public void sendRequest(int port, String request) {
        ConnectionResources resources = connections.get(port);
        if (resources != null && resources.socket.isConnected()) {
            resources.out.println(request);
        } else {
            System.out.println("No active connection on port " + port);
        }
    }

    public String getResponse(int port) {
        ConnectionResources resources = connections.get(port);
        if (resources != null && resources.socket.isConnected()) {
            try {
                return resources.in.readLine();
            } catch (IOException e) {
                System.out.println("Error getting response from server: " + e.getMessage());
            }
        } else {
            System.out.println("No active connection on port " + port);
        }
        return "Error: either resource is null or socket is not connected";
    }

    private Integer getServerPort(String path) {
        String serverName;
        int firstSlashIndex = path.indexOf("/");
        if (firstSlashIndex != -1) {
            serverName = path.substring(0, firstSlashIndex);
        } else {
            serverName = path;
        }

        return serverPortMap.get(serverName);
    }

    private void listenForUpdates(int port) {
        ConnectionResources resources = connections.get(port);
        if (resources == null) {
            System.out.println("No connection resources found for port " + port);
            return;
        }
        try {
            StringBuilder message = new StringBuilder();
            String line;
            while (!Thread.currentThread().isInterrupted()) {
                if (resources.in.ready()) {
                    while ((line = resources.in.readLine()) != null) {
                        message.append(line).append("\n");
                        if (line.equals("END_OF_DATA")) {
                            if (message.toString().startsWith("FILE_UPDATE")) {
                                handleFileUpdate(message.toString());
                                message = new StringBuilder(); // Reset message for the next update
                            }
                        }
                    }
                } else {
                    // If there's nothing to read, sleep a bit
                    try {
                        Thread.sleep(100); // Adjust the sleep time as necessary
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt(); // Preserve interruption status
                        break;
                    }
                }
            }
        } catch (IOException e) {
            if (!Thread.currentThread().isInterrupted()) {
                System.out.println("Error listening for updates: " + e.getMessage());
            } else {
                System.out.println("Interrupted during I/O operation.");
            }
        }
    }

    private void handleFileUpdate(String message) {
        String[] lines = message.split("\n");
        String header = lines[0];

        if (!header.startsWith("FILE_UPDATE:")) { // Include colon if it's part of the message
            System.out.println("\nInvalid message format");
            return;
        }

        String fileName = header.substring("FILE_UPDATE:".length()).trim();

        StringBuilder fileContent = new StringBuilder();
        for (int i = 1; i < lines.length - 1; i++) {
            fileContent.append(lines[i]).append("\n");
        }

        fileData.put(fileName, fileContent.toString().trim());

        System.out.println("\n File " + fileName + " has been updated.");
        System.out.print("cmd > ");
    }

    public void handleFileTransfer(int port, String fileName, String permission) {
        ConnectionResources resources = connections.get(port);
        if (resources == null) {
            System.out.println("No active connection on port " + port);
            return;
        }

        StringBuilder response = new StringBuilder();
        try {
            String line;
            while ((line = resources.in.readLine()) != null) {
                if ("END_OF_DATA".equals(line)) {
                    break; // Break the loop when the end-of-data marker is found
                }
                if (line.startsWith("Write access denied")) {
                    System.out.println(line);
                    return;
                } else if (line.startsWith("Error:")) {
                    System.out.println(line);
                    return;
                }
                response.append(line).append("\n");
            }
            fileData.put(fileName, response.toString().trim());
            filePermissions.put(fileName, permission);
            System.out.println("File opened: " + fileName);
        } catch (IOException e) {
            System.out.println("Error getting response: " + e.getMessage());
        }

        if ("r".equals(permission)) {
            System.out.println("Listening for updates");
            startListenForUpdates(fileName, port);
        }
    }

    public void listDirectory(String fullPath) {
        int firstSlashIndex = fullPath.indexOf("/");
        String serverName = (firstSlashIndex != -1) ? fullPath.substring(0, firstSlashIndex) : fullPath;
        String path = (firstSlashIndex != -1 && fullPath.length() > firstSlashIndex + 1)
                ? fullPath.substring(firstSlashIndex + 1)
                : "";

        Integer port = getServerPort(serverName);
        if (port != null) {
            sendRequest(port, "LS " + path);
            String response;
            while (!(response = getResponse(port)).equals("END_OF_LS")) {
                System.out.println(response);
            }
        } else {
            System.out.println("Server not found: " + serverName);
        }
    }

    public void openFile(int port, String fileName, String permission, Long startPosition, Long readLength) {
        if (fileName == null) {
            System.out.println("Filename not provided");
            return;
        }

        StringBuilder request = new StringBuilder("OPEN ").append(fileName).append(" ").append(permission);
        if (startPosition != null) {
            request.append(" ").append(startPosition);
            if (readLength != null) {
                request.append(" ").append(readLength);
            }
        }
        sendRequest(port, request.toString());
        handleFileTransfer(port, fileName, permission);
    }

    private void startListenForUpdates(String fileName, int port) {
        Thread updateListenerThread = new Thread(() -> listenForUpdates(port));
        updateListenerThread.start();
        threadMap.put(fileName, updateListenerThread);
    }

    public void readFile(int port, String fileName) {
        if (!filePermissions.containsKey(fileName) || !fileData.containsKey(fileName)) {
            System.out.println("File not open or not found: " + fileName);
            return;
        }
        String permission = filePermissions.get(fileName);
        if ("r".equals(permission) || "rw".equals(permission)) {
            String data = fileData.get(fileName);
            System.out.println("\n" + data + "\n");
        } else {
            System.out.println("Read permission denied for file: " + fileName);
        }
    }

    public void writeFile(int port, String fileName, int filePointer, String newData) {
        if (!filePermissions.containsKey(fileName) || !"rw".equals(filePermissions.get(fileName))) {
            System.out.println("Write permission denied for file: " + fileName);
            return;
        }

        if (newData == null) {
            System.out.println("No new data provided. Nothing to write.");
            return;
        }

        String nowData = fileData.getOrDefault(fileName, "");
        StringBuilder modifiedData = new StringBuilder();

        if (nowData.length() < filePointer) {
            modifiedData.append(nowData);
            for (int i = nowData.length(); i < filePointer; i++) {
                modifiedData.append(" ");
            }
        } else {
            modifiedData.append(nowData.substring(0, filePointer));
        }

        newData = newData.replace("\\n", "\n");
        modifiedData.append(newData);

        if (nowData.length() > filePointer + newData.length()) {
            modifiedData.append(nowData.substring(filePointer + newData.length()));
        }

        fileData.put(fileName, modifiedData.toString());
        System.out.println("Data added to file: " + fileName + " at position " + filePointer);
    }

    public void closeFile(int port, String fileName) {
        if (!filePermissions.containsKey(fileName)) {
            System.out.println("File not open: " + fileName);
            return;
        }
        String permission = filePermissions.get(fileName);
        if ("w".equals(permission) || "rw".equals(permission)) {
            System.out.println("close file");
            String content = fileData.get(fileName);
            sendRequest(port, "WRITE " + fileName);
            sendRequest(port, content);
            sendRequest(port, "END_OF_DATA");
            System.out.println(getResponse(port));
        }
        if ("r".equals(permission)) {
            Thread threadToStop = threadMap.get(fileName);
            if (threadToStop != null) {
                threadToStop.interrupt();
                threadMap.remove(fileName);
            }
        }
        fileData.remove(fileName);
        filePermissions.remove(fileName);
    }

    public static String[] parseFilePath(String filePath) {
        String serverName;
        String fileName;
        if (filePath.startsWith("/")) {
            filePath = filePath.substring(1);
        }

        int firstSlashIndex = filePath.indexOf("/");
        if (firstSlashIndex != -1) {
            serverName = filePath.substring(0, firstSlashIndex);
            fileName = filePath.substring(firstSlashIndex + 1);
        } else {
            serverName = filePath;
            fileName = "";
        }

        return new String[] { serverName, fileName };
    }

    public static void main(String[] args) throws IOException {
        System.out.println("Initiating connection with the server...");
        FileClient client = new FileClient();
        client.connectToServers("./serverList.txt");

        try (BufferedReader consoleReader = new BufferedReader(new InputStreamReader(System.in))) {
            System.out.println("Enter command (or type 'exit' to quit):");
            while (true) {
                System.out.print("cmd > ");
                String userInput = consoleReader.readLine();

                if ("exit".equalsIgnoreCase(userInput)) {
                    break;
                }
                handleUserInput(userInput, client);
            }
        } catch (IOException e) {
            System.out.println("Error occurred while reading user input: " + e.getMessage());
        } finally {
            client.stopConnection();
        }
    }

    public void createFile(int port, String fileName) {
        if (fileName == null || fileName.isEmpty()) {
            System.out.println("Filename not provided or empty.");
            return;
        }
        sendRequest(port, "CREATE_FILE " + fileName);
        System.out.println(getResponse(port));
    }

    public void createDirectory(int port, String dirName) {
        if (dirName == null || dirName.isEmpty()) {
            System.out.println("Directory name not provided or empty.");
            return;
        }
        sendRequest(port, "CREATE_DIR " + dirName);
        System.out.println(getResponse(port));
    }

    public void deleteFile(int port, String name) {
        if (name == null || name.isEmpty()) {
            System.out.println("File name not provided or empty.");
            return;
        }
        sendRequest(port, "DELETE " + name);
        System.out.println(getResponse(port));
    }

    private static void handleUserInput(String userInput, FileClient client) {
        String[] initialParts = userInput.split(" ", 3);
        if (initialParts.length < 2) {
            System.out.println("Invalid Command");
            return;
        }

        String command = initialParts[0].toUpperCase();
        String[] filePathParts = parseFilePath(initialParts[1]);
        String serverName = filePathParts[0];
        String fileName = filePathParts[1];

        Integer port = client.serverPortMap.get(serverName);
        if (port == null) {
            System.out.println("Server not found: " + serverName);
            return;
        }

        switch (command) {
            case "WRITE":
                if (initialParts.length < 3) {
                    System.out.println("Invalid Command. Usage: WRITE [serverName/filename] [filePointer] [newData]");
                    return;
                }
                String[] writeParts = initialParts[2].split(" ", 2);
                if (writeParts.length < 2) {
                    System.out.println("Invalid Command. Usage: WRITE [serverName/filename] [filePointer] [newData]");
                    return;
                }
                try {
                    int filePointer = Integer.parseInt(writeParts[0]);
                    String newData = writeParts[1];
                    client.writeFile(port, fileName, filePointer, newData);
                } catch (NumberFormatException e) {
                    System.out.println("Invalid file pointer: " + writeParts[0]);
                }
                break;
            case "OPEN":
                if (initialParts.length < 3) {
                    client.openFile(port, fileName, "r", null, null); // Default to read mode with full file
                    break;
                }
                String[] openParts = initialParts[2].split(" ", 2);
                String permission = openParts[0];
                Long startPosition = null;
                Long readLength = null;
                if (openParts.length > 1) {
                    String[] rangeParts = openParts[1].split(" ");
                    try {
                        startPosition = Long.parseLong(rangeParts[0]);
                        if (rangeParts.length > 1) {
                            readLength = Long.parseLong(rangeParts[1]);
                        }
                    } catch (NumberFormatException e) {
                        System.out.println("Invalid start position or read length: " + e.getMessage());
                        break;
                    }
                }
                client.openFile(port, fileName, permission, startPosition, readLength);
                break;
            case "READ":
                client.readFile(port, fileName);
                break;
            case "CLOSE":
                client.closeFile(port, fileName);
                break;
            case "CREATE_FILE":
                client.createFile(port, fileName);
                break;
            case "CREATE_DIR":
                client.createDirectory(port, fileName);
                break;
            case "DELETE":
                client.deleteFile(port, fileName);
                break;
            case "LS":
                String path = initialParts.length > 1 ? initialParts[1] : "";
                client.listDirectory(path);
                break;
            default:
                System.out.println("Invalid Command");
                break;
        }
    }

}