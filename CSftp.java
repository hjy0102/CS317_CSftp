import java.lang.*;
import java.net.*;
import java.util.*;
import java.io.*;
import java.util.regex.Pattern;
import java.util.regex.Matcher;

//
// This is an implementation of a simplified version of a command 
// line ftp client. The program always takes two arguments
//

public class CSftp {
    static final int MAX_LEN = 255;
    static final int ARG_CNT = 2;
    static CSftp ftp;
    static String workingDir;
    static boolean loggedIn = false; // user logged in is default false

    private static Socket control;
    private static DataOutputStream control_out;
    private static BufferedReader control_in;

    private static Socket data;
    private static DataOutputStream data_out;
    private static BufferedReader data_in;

    public static String handleError(String s) {
        switch (s) {
            case "0x001":
                System.out.println("0x001 Invalid command.");
                break;

            case "0x002":
                System.out.println("0x002 Incorrect number of arguments.");
                break;

            case "0xFFFC":
                System.out.println("0xFFFC Control connection I/O error, closing control connection.");
                try {
                    control.close();
                } catch (IOException closeException) {
                    handleError("");
                }
                System.exit(1);
                break;

            case "0x3A7":
                System.out.println("0x3A7 Data transfer connection I/O error, closing data connection.");
                try {
                    data.close();
                } catch (IOException closeException) {
                    handleError("");
                }
                System.exit(1);
                break;

            case "0xFFFD":
                System.out.println("0xFFFD Control connection I/O error, closing connection");
                quit();
                break;

            case "0xFFFE":
                System.out.println("0xFFFE Input error while reading commands, terminating.");
                quit();
                break;
        }
        return "Error message";
    }

    private static void handleError(String code, String msg) {
        switch (code) {
            case "0x38E":
                System.out.println(String.format("0x38E Access to local file %s denied.", msg));
                break;

            case "0xFFFF":
                System.out.println(String.format("0xFFFF Processing error. %s.", msg));
                break;
        }
    }

    private static void handleError(String code, String host, String port) {
        switch (code) {
            case "0x3A2":
                System.out.println(String.format("0x3A2 Data transfer connection to %s on port %s failed to open.", host, port));
                break;
        }
    }

    private static void handleError(int code, String msg) {
        System.out.println(String.format("Error %n: %s", code, msg));
    }

    public static void main(String[] args) {
        byte cmdString[] = new byte[MAX_LEN];
        String[] input;                // user input from command line

        // ftp connection set up
        ftp = new CSftp();
        workingDir = "";

        // Get command line arguments and connected to FTP
        // If the arguments are invalid or there aren't enough of them
        // then exit.

        if (args.length != ARG_CNT) {
            System.out.print("Usage: cmd ServerAddress ServerPort\n");
            return;
        }

        String hostName = args[0];
        int portNumber = Integer.parseInt(args[1]);
        // initialize control connection
        try {
            control = new Socket(hostName, portNumber);

            control_out = new DataOutputStream(control.getOutputStream());
            control_in = new BufferedReader(new InputStreamReader(control.getInputStream()));

            Response response = controlNext();

            switch (response.code) {
                case 220: // Service ready for new user.
                    // all good!
                    break;
                case 120: // Service ready in nnn minutes.
                case 421: // Service not available, closing control connection.
                    handleError("0xFFFD");
                    break;
            }
        } catch (IOException e) {
            handleError("");
        }

        try {
            for (int len = 1; len > 0; ) {
                System.out.print("csftp> ");
                len = System.in.read(cmdString);

                if (len <= 0)
                    break;
                else {
                    input = new String(cmdString, "ASCII").trim().split("#")[0].trim().split("\\s+", 2);
                }

                // Start processing the command here.
                switch (input[0].toLowerCase()) {


                    case "user":
                        if (input.length != 2) {
                            handleError("0x002");
                            break;
                        }
                        sendUser(input[1]);
                        break;
                    case "pw":
                        if (input.length != 2) {
                            handleError("0x002");
                            break;
                        }
                        sendPW(input[1]);
                        break;
                    case "quit":
                        if (input.length != 1) {
                            handleError("0x002");
                            break;
                        }
                        quit();
                        break;
                    case "get":
                        if (input.length != 2) {
                            handleError("0x002");
                            break;
                        }
                        getRemote(input[1]);
                        break;
                    case "features":
                        if (input.length != 1) {
                            handleError("0x002");
                            break;
                        }
                        renderFeatures();
                        break;
                    case "cd":
                        if (input.length != 2) {
                            handleError("0x002");
                            break;
                        }
                        changeDirectory(input[1]);
                        break;
                    case "dir":
                        if (input.length != 1) {
                            handleError("0x002");
                            break;
                        }
                        printDirList();
                        break;
                    default:
                        handleError("0xFFFF");
                }
                cmdString = new byte[MAX_LEN];
            }
        } catch (IOException exception) {
            System.err.println("0xFFFE Input error while reading commands, terminating.");
            quit();
        }
    }

    private static void sendString(String str) {
        try {
            printOut(str);
            control_out.writeBytes(str + "\r\n");
        } catch (IOException e) {
            handleError("0xFFFC");
        }
    }

    // send username to ftp server
    // user will need to pay attention to the
    // response code to determine if the password command must be sent
    private static void sendUser(String user) {

        sendString(String.format("USER %s", user));

        Response r = controlNext();
        switch (r.code) {
            case 230: // User logged in, proceed.
            case 530: // Not logged in.
            case 331: // User name okay, need password.
            case 332: // Need account for login.
                        // OK to not do anything. User should send next command.
                break;
            default: // 500, 501, 421
                handleError("0xFFFF", r.message);
                break;
        }

    }

    // send PW
    private static void sendPW(String pw) {
        sendString(String.format("PASS %s", pw));

        Response r = controlNext();
        switch (r.code) {
            case 230: // User logged in, proceed.
            case 202: // Command not implemented, superfluous at this site.
            case 530: // Not logged in.
            case 332: // Need account for login.
                // OK to not do anything. User should send next command.
                break;
            default: // 500, 501, 503, 421
                handleError("0xFFFF", r.message);
                break;
        }
    }

    // quit
    private static void quit() {
        sendString("QUIT");
        controlNext();
        System.exit(0);
    }

    private static boolean establishConnection() {
        sendString("PASV");

        Response r = controlNext();

        switch (r.code) {
            case 227: // Entering Passive Mode (h1,h2,h3,h4,p1,p2).
                Matcher matcher = Pattern.compile("[(](.*?)[)]").matcher(r.message);
                String[] ip_port;
                if (matcher.find()) {
                    ip_port = matcher.group(1).split(",");
                } else {
                    // NOTE: all errors that were not described in the assignment
                    // will be directed to 0xFFFF processing error
                    handleError("0xFFFF", r.message);
                    return false;
                }

                String hostname = ip_port[0] + "." + ip_port[1] + "." + ip_port[2] + "." + ip_port[3];

                int port = Integer.parseInt(ip_port[4]) * 256 + Integer.parseInt(ip_port[5]);

                data = new Socket();

                try {
                    data.connect(new InetSocketAddress(hostname, port), 80000);
                    data_out = new DataOutputStream(data.getOutputStream());
                    data_in = new BufferedReader(new InputStreamReader(data.getInputStream()));
                    return true;
                } catch (IOException e) {
                    handleError("0x3A2", hostname, Integer.toString(port));
                    return false;
                }

            default: // 500, 501, 502, 421, 530
                handleError("0xFFFF", r.message);
                break;
        }

        return false;
    }

    private static void saveFile(String filename) {
        OutputStream newFile;

        try {
            newFile = new FileOutputStream(filename);
        } catch (IOException err) {
            handleError("0x38E");
        }
    }

    // helper function for getRemote
    // check to see if data type is binary
    private static boolean binType(){
        sendString("TYPE I");
        Response r = controlNext();

        switch (r.code) {
            case 200: // all good
                return true;
            default: // 5xx and 4xx are errors
                handleError("0xFFFF", r.message);
                return false;
        }
    }

    // establish a data connection and retrieves the file indicated
    // by REMOTE, saving it in a file of the same name on the local machine
    private static void getRemote(String filename) {

        if (establishConnection()) {
            if (binType()) {
                sendString(String.format("RETR %s", filename));
                Response r = controlNext();

                switch (r.code) {
                    case 125: // data connection opened already, transfer starting
                    case 150: // file status ok, about to open data connection.
                        saveFile(filename);
                    break;
                    default: // 550
                        handleError("0xFFFF", r.message);
                    break;
                }
            } else { 
                handleError("0xFFFD"); // data transfer error
            }
            
        }
    }

    // changes the current working directory on the server
    // to the directory indicated by DIRECTORY
    private static void changeDirectory(String dir) {
        sendString(String.format("CWD %s", dir));

        Response r = controlNext();
        switch (r.code) {
            case 200: // Command okay.
            case 250: 
                // all good!
                break;
            default: // 500, 501, 502, 421, 530, 550
                handleError("0xFFFF", r.message);
                break;
        }
    }

    // print out everything on data socket to stdout
    // until the connection is closed
    private static String dataPrint() {
        try {
            String next;
            while ((next = data_in.readLine()) != null) {
                printIn(next);
            }
            return next;
        } catch (IOException e) {
            handleError("0x38E");
            try {
                data.close();
            } catch (IOException closeException) {
                handleError("0xFFFF", "Failed to close socket");
            }
            return null;
        }
    }

    // establishes a data connection and retrieves a list of files in the
    // current working directory on the server
    // list is printed to Standard Output
    // NOTE:: should depend on establishConnection()
    private static void printDirList() {
        if (establishConnection()) {
            sendString("LIST");
            Response listResp = controlNext();

            switch (listResp.code) {
                case 125: // Data connection already open; Transfer starting.
                case 150: // File status okay; about to open data connection
                    // proceed to printing data
                    dataPrint();

                    Response bList = controlNext();
                    switch (bList.code) {
                        case 226: // Closing data connection. Requested file action successful (for example, file transfer or file abort).
                        case 250: // Requested file action okay, completed.
                            // all good!
                            break;
                        case 425: // Can't open data connection.
                        case 426: // Connection closed; transfer aborted.
                        case 451: // Requested action aborted. Local error in processing.
                            handleError("0x38E");
                            break;
                    }
                    break;
                case 450: // Requested file action not taken. File unavailable (e.g., file busy).
                    handleError("0x38E");
                    break;
                default: // 500, 501, 502, 421, 530
                    handleError("0xFFFF", listResp.message);
                    break;
            }
        }
    }

    // prints all the features
    private static void renderFeatures() {

        sendString(String.format("FEAT"));

        Response r = controlNext();
        switch (r.code) {
            case 200: // Command okay.
            case 211: // System status, or system help reply.
            case 250: // Requested file action okay, completed.
                // all good!
                break;
            default: // 500, 501, 502, 421, 530, 550
                handleError("0xFFFF", r.message);
                break;
        }
    }

    // gets the next response from control socket
    // print to console
    public static Response controlNext() {
        try {
            String s = control_in.readLine();

            if (s.substring(3, 4).equals("-")) {
                System.out.println(String.format("<-- %s", s));

                String code = s.substring(0, 3);
                s = control_in.readLine();
                // keep printing lines until end of multiline
                // response is reached
                while (!(s.substring(0, 3).equals(code) &&
                        s.substring(3, 4).equals(" "))) {
                    System.out.println(String.format("<-- %s", s));
                    s = control_in.readLine();
                }
            }
            System.out.println(String.format("<-- %s", s));
            return parseResponse(s);
        } catch (IOException e) {
            //some error
            //handleError("");
            return null;
        }
    }

    public static Response parseResponse(String str) {
        String[] r = str.trim().split(" ", 2);

        if (r.length != 2) {
            r = Arrays.copyOf(r, r.length + 1);
            r[1] = "";
        }

        return new Response(Integer.parseInt(r[0]), r[1]);
    }

    private static void println(Object o) {
        System.out.println(o);
    }

    private static void print(Object o) {
        System.out.print(o);
    }

    private static void printIn(String str) {
        System.out.println(String.format("<-- %s", str));
    }

    private static void printOut(String str) {
        System.out.println(String.format("--> %s", str));
    }

    private static boolean isNum(String s) {
        return s.matches("[-=]?\\d*\\.?\\d+");
    }

}
