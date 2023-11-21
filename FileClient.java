import java.net.*;
import java.io.*;
import java.util.HashMap;
import java.util.Map;

public class FileClient {
    private Socket socket;
    private PrintWriter out;
    private BufferedReader in;
    private Map<String, String> fileData; // Stores the data of the files
    private Map<String, String> filePermissions; // Stores the permissions of the files

    public FileClient() {
        this.fileData = new HashMap<>();
        this.filePermissions = new HashMap<>();
    }

    public void startConnection(String ip, int port) {
        try {
            socket = new Socket(ip, port);
            out = new PrintWriter(socket.getOutputStream(), true);
            in = new BufferedReader(new InputStreamReader(socket.getInputStream()));
        } catch (IOException e) {
            System.out.println("Error starting connection: " + e.getMessage());
        }
    }

    public void stopConnection() {
        try {
            if (in != null) in.close();
            if (out != null) out.close();
            if (socket != null) socket.close();
        } catch (IOException e) {
            System.out.println("Error stopping connection: " + e.getMessage());
        }
    }

    public void sendRequest(String request) {
        out.println(request);
    }

    public String getResponse() {
        try {
            return in.readLine();
        } catch (IOException e) {
            System.out.println("Error getting response: " + e.getMessage());
            return null;
        }
    }

    public void handleFileTransfer(String fileName, String permission) {
        StringBuilder response = new StringBuilder();

        try {
            String line;
            while ((line = in.readLine()) != null) {
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

    public void openFile(String fileName, String permission) {
        if (fileName == null) {
            System.out.println("filename not provided");
            return;
        }
        sendRequest("OPEN " + fileName + " " + permission);
        handleFileTransfer(fileName, permission);
    }

    public void readFile(String fileName) {
        if (!filePermissions.containsKey(fileName) || !fileData.containsKey(fileName)) {
            System.out.println("File not open or not found: " + fileName);
            return;
        }
        String permission = filePermissions.get(fileName);
        if ("r".equals(permission) || "rw".equals(permission)) {
            String data = fileData.get(fileName);
            System.out.println("\n" + data + "\n");
        } else {
            System.out.println("Read permission denied for file: " + fileName + "\n");
        }
    }

    public void writeFile(String fileName, String newData) {
        String permission = filePermissions.get(fileName);
        if (!"w".equals(permission) && !"rw".equals(permission)) {
            System.out.println("Write permission denied for file: " + fileName + "\n");
            return;
        }
        if (newData == null) {
            System.out.println("No new data provided. Nothing to write.\n");
            return;
        }
        String currentData = fileData.getOrDefault(fileName, "");
        currentData += "\n" + newData;
        fileData.put(fileName, currentData);
    }

    public void closeFile(String fileName) {
        System.out.println();
        if (!filePermissions.containsKey(fileName)) {
            System.out.println("File not open: " + fileName);
            return;
        }
        String permission = filePermissions.get(fileName);
        if ("w".equals(permission) || "rw".equals(permission)) {
            String content = fileData.get(fileName);
            sendRequest("WRITE " + fileName);
            sendRequest(content);
            sendRequest("END_OF_DATA");
            System.out.println(getResponse());
        }
        sendRequest("CLOSE " + fileName);
        System.out.println(getResponse() + "\n");
        fileData.remove(fileName);
        filePermissions.remove(fileName);
    }

    public static void main(String[] args) throws IOException {
        FileClient client = new FileClient();
        client.startConnection("127.0.0.1", 6666);

        try (BufferedReader consoleReader = new BufferedReader(new InputStreamReader(System.in))) {
            System.out.println("Enter command (or type 'exit' to quit): ");
            while (true) {
                System.out.print("cmd > ");
                String userInput = consoleReader.readLine();

                if ("exit".equalsIgnoreCase(userInput)) {
                    break;
                }
                
                handleUserInput(userInput, client);
            }
        } catch (IOException e) {
            System.out.println("Error occured while reading user input: " + e.getMessage());
        } finally {
            client.stopConnection();
        }
    }

    private static void handleUserInput(String userInput, FileClient client) {
        String[] commandParts = userInput.split(" ", 3);
        if (commandParts.length == 0) {
            System.out.println("Invalid Command");
            return;
        }

        String command = commandParts[0];
        String fileName = commandParts.length > 1 ? commandParts[1] : null;

        switch (command.toUpperCase()) {
            case "OPEN":
                String permission = commandParts.length > 2 ? commandParts[2] : null;
                client.openFile(fileName, permission);
                break;
            case "READ":
                client.readFile(fileName);
                break;
            case "WRITE":
                String newData = commandParts.length > 2 ? commandParts[2] : null;
                client.writeFile(fileName, newData);
                break;
            case "CLOSE":
                client.closeFile(fileName);
                break;
            default:
                System.out.println("Invalid Command");
                break;
        }
    }
}
