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

    public void startConnection(String ip, int port) throws IOException {
        socket = new Socket(ip, port);
        out = new PrintWriter(socket.getOutputStream(), true);
        in = new BufferedReader(new InputStreamReader(socket.getInputStream()));
    }

    public void sendRequest(String request) {
        out.println(request);
    }

    public String getResponse() throws IOException {
        StringBuilder response = new StringBuilder();
        int character;

        while ((character = in.read()) != -1) {
            response.append((char) character);
            if (in.ready()) {  // Check if more data is available to read
                continue;
            }
            break; // Break if no more data is available immediately
        }

        return response.toString();
    }

    public void openFile(String fileName, String permission) throws IOException {
        sendRequest("OPEN " + fileName + " " + permission);
        String response = getResponse();

        int confirmationIndex = response.lastIndexOf("\nFile opened with");
        if (confirmationIndex != -1) {
            String fileContent = response.substring(0, confirmationIndex);
            String confirmationMessage = response.substring(confirmationIndex + 1).trim();

            fileData.put(fileName, fileContent);
            filePermissions.put(fileName, permission);
    
            System.out.println(confirmationMessage + "\n");
        } else {
            System.out.println("Unable to parse server response.");
        }
    }

    public void readFile(String fileName) {
        String permission = filePermissions.get(fileName);

        if ("r".equals(permission) || "rw".equals(permission)) {
            String data = fileData.get(fileName);
            System.out.println("File data: \n" + data);
        } else {
            System.out.println("Read permission denied for file: " + fileName + "\n");
        }    
    }

    public void writeFile(String fileName, String newData) {
        if ("w".equals(filePermissions.get(fileName)) || "rw".equals(filePermissions.get(fileName))) {
            String currentData = fileData.getOrDefault(fileName, "");
            currentData += "\n" + newData;
            fileData.put(fileName, currentData);
        } else {
            System.out.println("Write permission denied for file: " + fileName);
        }
    }

    public void closeFile(String fileName) throws IOException {
        if (filePermissions.containsKey(fileName)) {
            if ("w".equals(filePermissions.get(fileName)) || "rw".equals(filePermissions.get(fileName))) {
                String content = fileData.get(fileName);
                content += "<END_OF_DATA>";
                sendRequest("WRITE " + fileName + " " + content);
                System.out.println(getResponse());
            }
            sendRequest("CLOSE " + fileName);
            System.out.println(getResponse());
            fileData.remove(fileName);
            filePermissions.remove(fileName);
        } else {
            System.out.println("File not open: " + fileName);
        }
    }

    public void stopConnection() throws IOException {
        in.close();
        out.close();
        socket.close();
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

                String[] commandParts = userInput.split(" ", 3);
                String command = commandParts[0];
                String fileName = commandParts.length > 1 ? commandParts[1] : null;

                System.out.println();
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
        } finally {
            client.stopConnection();
        }
    }
}
