//Emmeline Pearson z5178618
import java.net.*;
import java.io.*;
import java.util.Random;
import java.util.Arrays;


//example run call java Reciever <receiver_port> <file.txt>

public class receiver {
	
	final static byte SYN = 0x1; // 00000001
	final static byte ACK = 0x2; // 00000010
	final static byte FIN = 0x4; // 00000100
	final static int HEADER_SIZE = 9;
	final static int BUFFER_SIZE = 100;
	
	private static DatagramSocket socket;
	private static DatagramPacket pktIn;
	private static DatagramPacket pktOut;
	
	private static Timer timer;
	private static int currentAckNum;
	private static int currentSeqNum;
	private static int initialAckNum;

	private static byte[][] fileDataBuffer;
	private static String filename;
	private static int receivedPktCount;
	private static int receivedByteCounter;
	private static PrintWriter receiverLog;
	
	public static void main(String[] args) throws IOException {
		// Check if correct num of input args 
		if (args.length != 2) {
			System.out.println("Required arguments: receiver_port file.txt");
			return;
		}
	
		receiverLog = new PrintWriter("Receiver_log.txt");
		
		// input args to variables 
		int receiver_port = Integer.parseInt(args[0]);
		filename = args[1];
		socket = new DatagramSocket(receiver_port, InetAddress.getByName(InetAddress.getLocalHost().getHostAddress()));
		
		// Initialize counts for end print
		receivedPktCount = 0;
		receivedByteCounter = 0;
		pktIn = new DatagramPacket(new byte[1024], 1024);
		pktOut = new DatagramPacket(new byte[HEADER_SIZE], HEADER_SIZE);
		timer = new Timer();
		timer.start();
		
		handShake();

		fileDataBuffer = new byte[BUFFER_SIZE][];
		
		while (true) {
			// receive pkt
			socket.receive(pktIn);
			printPacketData("rcv", pktIn);
			
			// Update ack and Sequence nums
			currentAckNum = getPacketSeqNum(pktIn) + pktIn.getLength() - HEADER_SIZE;
			currentSeqNum += 1;
			
			if (pktIn.getData()[0] == FIN) { //if finish close the connection 
				break;
			} 
			else {
				receivedPktCount ++;
				receivedByteCounter += (pktIn.getLength() - HEADER_SIZE);
				
				byte[] data = pktIn.getData();
				
				// write data -> buffer
				byte[] copyData = Arrays.copyOf(data, pktIn.getLength());
				fileDataBuffer[(getPacketSeqNum(pktIn)-initialAckNum)/(pktIn.getLength()-HEADER_SIZE)] = copyData;
				
				//// ack received pkt fill ACK pkt w/ data
				data = new byte[HEADER_SIZE];
				Arrays.fill(data, (byte) 0);
				data[0] = ACK;
				System.arraycopy(int2ByteArray(currentSeqNum), 0, data, 1, 4);
				System.arraycopy(int2ByteArray(currentAckNum), 0, data, 5, 4);
				pktOut.setData(data, 0, data.length);
				
				//Send ACK pkt
				socket.send(pktOut);	
				printPacketData("snd", pktOut);
				
				}
		}
		
		// Close connection
		terminateConnection();
		//transferred data -> file
		writeMessage();
		
		//print final log lines
		receiverLog.println("Data Segments received: " + receivedPktCount);
		receiverLog.println("Total Bytes received: " + receivedByteCounter);
		receiverLog.println("Duplicate segments received: 0"); //always will be zero
		
		
		receiverLog.close(); //close log writer
		timer.kill();//Stop timer
	}
	
	private static void handShake() throws IOException {
		currentSeqNum = new Random().nextInt(10000);
		
		////--------RECEIVE SYN----------------
		while (pktIn.getData()[0] != SYN) {
			socket.receive(pktIn);
		}
		// match pktOut's destination address and port to address of pktIn
		pktOut.setAddress(pktIn.getAddress());
		pktOut.setPort(pktIn.getPort());
		// update ack num 
		currentAckNum = 1 + getPacketSeqNum(pktIn);
		
		//pkt data -> log
		printPacketData("rcv", pktIn);
		
		///-----------------------SEND SYN ACK---------------------------
		// byte array for hadshake
		byte[] data = new byte[HEADER_SIZE];
		data[0] = SYN|ACK;
		//Add Sequence and ack nums to data
		System.arraycopy(int2ByteArray(currentSeqNum), 0, data, 1, 4);
		System.arraycopy(int2ByteArray(currentAckNum), 0, data, 5, 4);
		
		pktOut.setData(data, 0, data.length);//Add data to packet
		socket.send(pktOut);//Send packet
		printPacketData("snd", pktOut);//Write packet data to log
		
		////--------------------RECEIVE ACK-------------------------
		while (pktIn.getData()[0] != ACK) {
			socket.receive(pktIn);
		}
		
		// Write packet data to log
		printPacketData("rcv", pktIn);
		
		//Store starting ack num for later calculations
		initialAckNum = currentAckNum;
	}
	
	private static void terminateConnection() throws IOException {
		
		//create byte array for termination pkts
		byte[] data = new byte[HEADER_SIZE];
		//------Send FIN ACK
		data[0] = (FIN|ACK);
		System.arraycopy(int2ByteArray(currentSeqNum), 0, data, 1, 4);
		System.arraycopy(int2ByteArray(currentAckNum), 0, data, 5, 4);
		pktOut.setData(data, 0, data.length);
		socket.send(pktOut);
		printPacketData("snd", pktOut);
		
		//--------Send FIN
		data[0] = (FIN);
		System.arraycopy(int2ByteArray(currentSeqNum), 0, data, 1, 4);
		System.arraycopy(int2ByteArray(currentAckNum), 0, data, 5, 4);
		pktOut.setData(data, 0, data.length);
		socket.send(pktOut);
		printPacketData("snd", pktOut);
		
		//-------Receive FIN ACK
		while (pktIn.getData()[0] != (FIN|ACK)) {
			socket.receive(pktIn);
		}
		printPacketData("rcv", pktIn);
		
		//--------Close the socket
		socket.close();

	}
	
	// converts bytes to an array 
	private static byte[] int2ByteArray( int num ) {
		byte[] result = new byte[4];
		result[3] = (byte) ((num & 0x000000FF) >> 0);
		result[2] = (byte) ((num & 0x0000FF00) >> 8);
		result[1] = (byte) ((num & 0x00FF0000) >> 16);
		result[0] = (byte) ((num & 0xFF000000) >> 24);
		return result;
	}
	
	// gets a packet's sequence number (bytes 1-4 of data converted to an int)
	private static int getPacketSeqNum(DatagramPacket p) 
	{
		int result = ((p.getData()[1] & 0xFF) << 24)
			| ((p.getData()[2] & 0xFF) << 16)
		        | ((p.getData()[3] & 0xFF) << 8)
		        | (p.getData()[4] & 0xFF);
		return result;
	}
	
	// Helper method to get a packet's acknowledgement number
	// Extracts bytes 5-8 of its data, and arranges it into an int
	private static int getPacketAckNum(DatagramPacket p) 
	{
		int result = ((p.getData()[5] & 0xFF) << 24)
			| ((p.getData()[6] & 0xFF) << 16)
		        | ((p.getData()[7] & 0xFF) << 8)
		        | (p.getData()[8] & 0xFF);
		return result;
	}
	
	// Writes all the data in the buffer out to the file
	private static void writeMessage() throws FileNotFoundException, UnsupportedEncodingException 
	{
		PrintWriter writer = new PrintWriter(filename);
		for (byte[] segmentData: fileDataBuffer)
		{
			if (segmentData == null) break;
			for (int i = HEADER_SIZE; i < segmentData.length ; i++) 
			{
				if (segmentData[i] == 0) break;
				writer.print((char)segmentData[i]);
			}
		}
		writer.close();
	}
	
	// Prints the packet's data to the receiver log
	// Needs to know whether the packet is incoming, outgoing, or dropped
	private static void printPacketData(String packetStatus, DatagramPacket p) 
	{
		receiverLog.print(packetStatus);
		receiverLog.print(" " + String.format("%6s", timer.getTimeElapsed()));
		
		if (p.getData()[0] == SYN) receiverLog.print("  S ");  //Syn		
		if (p.getData()[0] == 0) receiverLog.print("  D ");   //Data 
		if (p.getData()[0] == ACK) receiverLog.print("  A ");  //Acknowledgement
		if (p.getData()[0] == (SYN|ACK)) receiverLog.print(" SA "); //Syn Ack 
		if (p.getData()[0] == (FIN|ACK)) receiverLog.print(" FA "); //Finish Ack
		if (p.getData()[0] == FIN) receiverLog.print("  F ");   //Finish
		
		receiverLog.print(String.format("%5s", getPacketSeqNum(p)));
		receiverLog.print(" " + String.format("%4s",(p.getLength() - HEADER_SIZE)));
		receiverLog.println(" " + String.format("%5s", getPacketAckNum(p)));
	}
}
