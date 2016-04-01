package utd.aos.client;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.OutputStream;
import java.net.ConnectException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.UnknownHostException;
import java.util.HashMap;

import java.util.Map;
import java.util.PriorityQueue;
import java.util.Random;
import java.util.concurrent.Semaphore;

import utd.aos.utils.Message;
import utd.aos.utils.MutexMessage;
import utd.aos.utils.MutexMessage.MessageType;
import utd.aos.utils.Operations;
import utd.aos.utils.Resource;
import utd.aos.utils.SocketMap;
import utd.aos.utils.Operations.OperationMethod;
import utd.aos.utils.Operations.OperationType;

public class Client implements Runnable{
	
	public static int id;
	public static Map<String, SocketMap> quorum;
	public static Map<Integer, InetSocketAddress> otherClients;
	public static Map<String, Integer> hostIdMap;
	
	public static SocketMap serverSocketMap;
	
	public static InetAddress ip;
	public static Integer port;
	
	public static Semaphore mutex = new Semaphore(1);	
	
	public static Semaphore gotallReplies = new Semaphore(1);
	public static Semaphore gotallReleases = new Semaphore(1);
	public static Semaphore gotReplyofEnquire = new Semaphore(1);
	
	public static int pendingReleaseToReceive;
	public static int pendingReplyofEnquire;
	
	public static Map<Integer, Boolean> pendingRepliesToReceive = new HashMap<Integer, Boolean>();
	public static Map<Integer, Boolean> gotFailedMessageFrom = new HashMap<Integer, Boolean>();
	public static Map<Integer, Boolean> sentYieldMessageTo = new HashMap<Integer, Boolean>();
	
	public static PriorityQueue<Integer> fifo = new PriorityQueue<Integer>();
	
	public State state = State.AVAILABLE;
	public enum State {
		AVAILABLE, BLOCKED 
	}
	
	public Client() {
		
	}

	//MutexAlgorithm algo;
	
	//blocked: either me executing critical section or didn't get the release from last reply.
	
	@Override
	public void run() {
		// TODO Auto-generated method stub
		init();
		int count = 1;
		while(count <= 40) {
			Random rand = new Random();
			Integer delay = rand.nextInt(40);
			delay += 10;

			try {
				Thread.sleep(delay);

				Resource resource = new Resource();
				resource.setFilename("test");

				Operations operation = new Operations();
				operation.setOperation(OperationMethod.WRITE);
				operation.setType(OperationType.PERFORM);
				operation.setInputResource(resource);
				operation.setArg(id+" : "+count+" : "+InetAddress.getLocalHost().getHostName()+"\n");

				
				if(getMutex()) {
					System.out.println("--starting CS--");
					request(operation);
					System.out.println("--done with CS--");

				}
				sendRelease();
			} catch (Exception e) {
				e.printStackTrace();
			}
			count++;
		}
		shutdown();			
	}

	
	public void init() {
		
		//Check for all clients to be started
		for(Map.Entry<Integer, InetSocketAddress> entry: otherClients.entrySet()) {
			InetSocketAddress addr = entry.getValue();
			while(true) {
			    try {	    	
					Socket socket = new Socket(addr.getHostName(), addr.getPort());
					if(quorum.containsKey(addr.getHostName())) {
						InputStream in = socket.getInputStream();
						OutputStream out = socket.getOutputStream();

						ObjectInputStream o_in = new ObjectInputStream(in);
						ObjectOutputStream o_out = new ObjectOutputStream(out);
						System.out.println("--Saving streams--");
						MutexMessage testmessage = new MutexMessage();
						testmessage.setType(MessageType.TEST);
						o_out.writeObject(testmessage);
						quorum.put(addr.getHostName(), (new SocketMap(socket, o_out, o_in, addr)));
						break;
					}
					System.out.println("Connect success: "+ip.getHostName()+"->"+addr.getHostName());
					break;
			    } catch(ConnectException e) {
			        System.out.println("Connect failed, waiting and trying again: "+ip.getHostName()+"->"+addr.getHostName());
			        try {
			            Thread.sleep(1000);
			        }
			        catch(InterruptedException ie) {
			            ie.printStackTrace();
			        }
			    } catch (UnknownHostException e) {
					e.printStackTrace();
				} catch (IOException e) {
					e.printStackTrace();
				} 
			}
		}

	}
	
	public void shutdown() {
		
	}
	public void reset() {
		pendingReleaseToReceive = 0;
		pendingReplyofEnquire = 0;
		
		pendingRepliesToReceive = new HashMap<Integer, Boolean>();
		gotFailedMessageFrom = new HashMap<Integer, Boolean>();
		sentYieldMessageTo = new HashMap<Integer, Boolean>();
		
	}
	
	public boolean getMutex() throws InterruptedException, IOException {
		
		System.out.println("--trying to acquire mutex--");
		gotallReleases.acquire();
		
		System.out.println("--got release semaphore--");
		gotallReplies.acquire();
		
		reset();
		
		System.out.println("--got reply semaphore--");

		for(Map.Entry<String, SocketMap> entry: quorum.entrySet()) {
			SocketMap quorum_client = entry.getValue();
			String hostname = quorum_client.getAddr().getHostName();
			Integer client_id = hostIdMap.get(hostname);
			MutexMessage message = new MutexMessage(id, MessageType.REQUEST);
			
			System.out.println("--sending request message to "+hostname+"--");
			
			quorum_client.getO_out().writeObject(message);
			pendingRepliesToReceive.put(client_id, true);
			
			ClientsServerThreadListener clientServer = new ClientsServerThreadListener(quorum_client);
			Thread t = new Thread(clientServer);
			t.start();
		}
		gotallReplies.acquire();
		gotallReplies.release();
		gotallReleases.release();

		return true;
	}
	
	public void sendRelease() throws IOException {
		System.out.println("--send release to all--");
		for(Map.Entry<String, SocketMap> entry: quorum.entrySet()) {
			SocketMap quorum_client = entry.getValue();
			MutexMessage message = new MutexMessage(id, MessageType.RELEASE);
			quorum_client.getO_out().writeObject(message);
		}
	}
	
	public Message request(Operations operation) throws IOException, ClassNotFoundException {	
		//creating the request
		System.out.println("--sending request to server--");

		if(operation.getOperation().equals(OperationMethod.CREATE)) {
			Resource resource = operation.getInputResource();
			File file = new File(resource.getFilename());
			if(file.exists()) {
				String fileContent = "";
				BufferedReader br = new BufferedReader(new FileReader(file));
				String line = "";
				while((line = br.readLine()) != null ) {
					fileContent += line;
				}					
				resource.setFileContent(fileContent);
				br.close();
				
				operation.setInputResource(resource);
			}
		}
		
		//sends the operation request
		serverSocketMap.getO_out().writeObject(operation);
		
		//wait for the response
		Object object = serverSocketMap.getO_in().readObject();
		
		
		Message m;
		
		if (object instanceof Message) {
			 m = (Message)object;
			 return m;
		} else {
			return null;
		}
	}

	public int getId() {
		return id;
	}

	public SocketMap getServerSocketMap() {
		return serverSocketMap;
	}

	public Map<String, SocketMap> getQuorum() {
		return quorum;
	}


	public InetAddress getIp() {
		return ip;
	}

	public Integer getPort() {
		return port;
	}


	public Map<Integer, InetSocketAddress> getOtherClients() {
		return otherClients;
	}


	public Map<String, Integer> getHostIdMap() {
		return hostIdMap;
	}


}
