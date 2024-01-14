import java.net.*;
import java.io.*;
import java.util.HashMap;
import java.util.Map;
import java.util.ArrayList;
import java.util.List;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.stream.Stream;

public class FileClient {
    private Socket socket;
    private PrintWriter out;
    private BufferedReader in;
    private Map<String, String> fileData; // Stores the data of the files
    private Map<String, String> filePermissions; // Stores the permissions of the files
    private List<Socket> connections;

    public FileClient() {
        this.fileData = new HashMap<>();
        this.filePermissions = new HashMap<>();
        connections = new ArrayList<>();
    }

    public void connectToServers(String fileName) {
	    try (Stream<String> stream = Files.lines(Paths.get(fileName))) {
		    stream.forEach(line -> {
			    String[] parts = line.split(" ");
			    if (parts.length == 2) {
				    startConnection("127.0.0.1", Integer.parseInt(parts[0]), parts[1]);
			    }
		    });
	    } catch (IOException e) {
		    System.out.println("Error reading server list from file");
		    e.printStackTrace();
	    }
    }

    public void startConnection(String ip, int port, String serverName) {
        try {
            Socket socket = new Socket(ip, port);
	    connections.add(socket);
            in = new BufferedReader(new InputStreamReader(socket.getInputStream()));
            System.out.println("Successfully started connection with server: " + serverName);
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
            System.out.println("Error getting response from server: " + e.getMessage());
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

    public parseFilePath(String filePath) {
	if (filePath.startsWith("/") {
		filePath = filePath.substring[1];
	}

	String[] parts = filePath.split("/");
	serverName = parts[0];
	fileName = parts[parts.length - 1];

	if (parts.length > 2) {
		folders = new String[parts.length - 2];
		System.arraycopy(parts, 1, folder, 0, parts.length - 2);
	} else {
		folders = new String[0];
	}
	return serverName, fileName, folders;
    }
		

    public static void main(String[] args) throws IOException {
        FileClient client = new FileClient();
	client.connectToServers("serverList.txt");

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

    private static void handleUserInput(String userInput, FileClient client) {
        String[] commandParts = userInput.split(" ", 3);
        if (commandParts.length == 0) {
            System.out.println("Invalid Command");
            return;
        }

        String command = commandParts[0];
	String serverName, fileName, folders = parseFilePath(commandParts[1]);

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
