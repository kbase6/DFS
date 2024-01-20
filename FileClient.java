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

public class FileClient {
    private Map<String, String> fileData;
    private Map<String, String> filePermissions;
    private Map<Integer, ConnectionResources> connections;
    private Map<String, Integer> serverPortMap;

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
        return null;
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
            while ((line = resources.in.readLine()) != null) {
                message.append(line).append("\n");
                if (line.equals("END_OF_DATA")) {
                    if (message.toString().startsWith("FILE_UPDATE")) {
                        handleFileUpdate(message.toString());
                        message = new StringBuilder(); // Reset message for the next update
                    }
                }
            }
        } catch (IOException e) {
            System.out.println("Error listening for updates: " + e.getMessage());
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

        System.out.println("File " + fileName + " has been updated.");
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
                    System.out.println("\n" + line + "\n");
                    return;
                }
                response.append(line).append("\n");
            }
            fileData.put(fileName, response.toString().trim());
            filePermissions.put(fileName, permission);
            System.out.println("\nFile opened: " + fileName + "\n");
        } catch (IOException e) {
            System.out.println("Error getting response: " + e.getMessage());
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

        if ("r".equals(permission)) {
            System.out.println("Listening for updates");
            startListenForUpdates(port);
        }
    }

    private void startListenForUpdates(int port) {
        Thread updateListenerThread = new Thread(() -> listenForUpdates(port));
        updateListenerThread.start();
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

        String nowData = fileData.get(fileName);
        String modifiedData = nowData.substring(0, filePointer) + newData + nowData.substring(filePointer);
        fileData.put(fileName, modifiedData);
        System.out.println("Data added to file: " + fileName + " at position " + filePointer);
    }

    public void closeFile(int port, String fileName) {
        if (!filePermissions.containsKey(fileName)) {
            System.out.println("File not open: " + fileName);
            return;
        }
        String permission = filePermissions.get(fileName);
        if ("w".equals(permission) || "rw".equals(permission)) {
            String content = fileData.get(fileName);
            sendRequest(port, "WRITE " + fileName);
            sendRequest(port, content);
            sendRequest(port, "END_OF_DATA");
            System.out.println(getResponse(port));
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
        sendRequest(port, "CREATE_FILE " + fileName);
        String response = getResponse(port);
        System.out.println("\n" + response + "\n");
    }

    public void createDirectory(int port, String dirName) {
        sendRequest(port, "CREATE_DIR " + dirName);
        String response = getResponse(port);
        System.out.println("\n" + response + "\n");
    }

    public void deleteFile(int port, String name) {
        sendRequest(port, "DELETE " + name);
        String response = getResponse(port);
        System.out.println("\n" + response + "\n");
    }

    private static void handleUserInput(String userInput, FileClient client) {
        String[] commandParts = userInput.split(" ", 6);
        for (int i = 0; i < commandParts.length; i++) {
            commandParts[i] = commandParts[i].trim();
        }

        if (commandParts.length < 2) {
            System.out.println("Invalid Command");
            return;
        }

        String command = commandParts[0].toUpperCase();
        String[] parsedFilePath = parseFilePath(commandParts[1]);
        String serverName = parsedFilePath[0];
        String fileName = parsedFilePath[1];

        Integer port = client.serverPortMap.get(serverName);
        if (port == null) {
            System.out.println("Server not found: " + serverName);
            return;
        }

        switch (command.toUpperCase()) {
            case "LS":
                String path = commandParts.length > 1 ? commandParts[1] : "";
                client.listDirectory(path);
                break;
            case "OPEN":
                String permission = commandParts.length > 2 ? commandParts[2] : "r";
                Long startPosition = null;
                Long readLength = null;
                if (commandParts.length > 3) {
                    try {
                        startPosition = Long.parseLong(commandParts[3]);
                        if (commandParts.length > 4) {
                            readLength = Long.parseLong(commandParts[4]);
                        }
                    } catch (NumberFormatException e) {
                        System.out.println("Invalid start position or read length: " + e.getMessage());
                        return;
                    }
                }
                client.openFile(port, fileName, permission, startPosition, readLength);
                break;
            case "READ":
                client.readFile(port, fileName);
                break;
            case "WRITE":
                if (commandParts.length < 4) {
                    System.out.println("Invalid Command. Usage: WRITE [serverName/filename] [filePointer] [newData]");
                    return;
                }
                try {
                    int filePointer = Integer.parseInt(commandParts[2]);
                    String newData = commandParts[3];
                    client.writeFile(port, fileName, filePointer, newData);
                } catch (NumberFormatException e) {
                    System.out.println("Invalid file pointer: " + commandParts[2]);
                }
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
            default:
                System.out.println("Invalid Command");
                break;
        }
    }
}