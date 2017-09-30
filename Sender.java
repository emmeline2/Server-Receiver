//Emmeline Pearson z5178618
import java.net.*;
import java.io.*;
import java.util.Random;
import java.util.Arrays;
import java.util.LinkedList;


//example call to Sender.java: java Sender 129.94.242.117 1988 file.txt 500 50 0.5 300 
					 // host IP       port  file    MWS MSS pdrop seed   

//sender log format: <snd/rcv/drop>  <time>  <type  of  packet>  <seq-number>  <number-of-bytes> <ack-number>    


public class sender {
	
	//SYN - ACK - FIN: flags 
	final static byte SYN = 0x1; // 00000001
	final static byte ACK = 0x2; // 00000010
	final static byte FIN = 0x4; // 00000100
	
	final static int HEADER_SIZE = 9;
	private static DatagramPacket pktOut;
	private static DatagramPacket pktIn;

	//input arguments
	private static InetAddress receiverHostIP; //use the IP address of computer
	private static int receiverPort; //used something around 2080	
	private static int MWS; //maximum window size
	private static int MSS; //maximum segment size 
	private static int timeout; //bigger than 50
	private static String filepath; //file.txt - file transmitting
	private static double pdrop; //.95  = dropped 95% of time 
	private static int seed; //like 200

	private static DatagramSocket socket;
	private static int currentAckNum;
	private static int initialSeqNum;
	private static int currentSeqNum;
	private static PrintWriter log;
	private static Random r;
	
	private static int bytesTransfCount; //bytes transferred counter
	private static int segsSentCount; //segments sent counter
	private static int retransSegCount; //retransmitted segment counter
	private static int droppedPktCount; //dropped packet counter
	private static Timer timer;
	
	public static void main(String[] args) throws IOException {
		if (args.length != 8) {
			System.out.println("Required arguments:receiver_host_ip receiver_port file.txt MWS MSS timeout pdrop seed");
			return;
		}
		
		// assign all arguments to variables 
		receiverHostIP = InetAddress.getByName(args[0]);
		receiverPort = Integer.parseInt(args[1]);
		filepath = args[2];
		MWS = Integer.parseInt(args[3]);
		MSS = Integer.parseInt(args[4]);
		timeout = Integer.parseInt(args[5]);
		pdrop = Double.parseDouble(args[6]);
		r = new Random(seed);
		pktOut = new DatagramPacket(new byte[1024], 1024);
		pktIn = new DatagramPacket(new byte[1024], 1024);
		

		//Sender Log Writer
		log = new PrintWriter("Sender_log.txt"); 
		
		//create UDP socket
		socket = new DatagramSocket();
		socket.setSoTimeout(timeout);
		
		//create Packet's address
		pktOut.setAddress(receiverHostIP);
		pktOut.setPort(receiverPort);
		
		//begin timer
		timer = new Timer();
		timer.start();
		
		//Initialize counts (used for log)
		bytesTransfCount = 0;
		segsSentCount = 0;
		droppedPktCount = 0;
		retransSegCount = 0;
		
		//------------------start handshake---------------
		handShake();
		
		//processing file
		File file = new File(filepath);
		InputStream in = new FileInputStream(file);
		
		// create pkt's byte array
		byte[] data = new byte[MSS + HEADER_SIZE];
		
		int unAckedBytes = 0;
		LinkedList<DatagramPacket> unAckedPackets = new LinkedList<DatagramPacket>(); //linked list for unAcked pkts

		
		//---------------------Sending of data----------------------
		while (true) {
			// If room in window create and send pkts
			if (unAckedBytes < MWS && file.length() > currentSeqNum - initialSeqNum) {
				
				//put data in array (sequence/ack nums, then file data)
				Arrays.fill(data, (byte) 0);
				System.arraycopy(int2ByteArray(currentSeqNum), 0, data, 1, 4);
				System.arraycopy(int2ByteArray(currentAckNum), 0, data, 5, 4);
				in.read(data, HEADER_SIZE, MSS);
				pktOut.setData(data);// Put data --> pkt
				
				//pkt sent or dropped -> writen to log
				segsSentCount++;
				bytesTransfCount+= (pktOut.getLength() - HEADER_SIZE);
				if (r.nextDouble() > pdrop) //pkt sent
				{
					printPacketData("snd", pktOut);
					socket.send(pktOut);
				} 
				

				else //pkt dropped
				{ 
					droppedPktCount++;
					printPacketData("drp", pktIn); 
				}
				
				//Update Sequence num and unacked byte count
				currentSeqNum += (pktOut.getLength() - HEADER_SIZE);
				unAckedBytes += (pktOut.getLength() - HEADER_SIZE);

				//Store copy pkt to queue
				byte[] tempData = Arrays.copyOf(data, MSS+HEADER_SIZE);
				DatagramPacket newCopy = new DatagramPacket(tempData, tempData.length);
				unAckedPackets.add(newCopy);
				
				//Continue with loop
				continue;
			}
			
			// If no packets to send, wait for an ACK
			try {
				socket.receive(pktIn);
				
				//Update ACK num based on incoming pkt seq num
				currentAckNum = getPacketSeqNum(pktIn) + 1;

				// received pkt data -> log
				printPacketData("rcv", pktIn);
				
				//If pkt is an ACK (it should be)remove corresponding pkt from unACKed pkt list
				if (pktIn.getData()[0] == ACK) 
				{
					DatagramPacket toRemove = null;
					for (DatagramPacket packet: unAckedPackets) 
					{
						if (getPacketSeqNum(packet) + packet.getLength() - HEADER_SIZE == getPacketAckNum(pktIn)) 							{
							toRemove = packet;
							break;
						}
					}
					unAckedPackets.remove(toRemove);
					unAckedBytes -= (toRemove.getLength() - HEADER_SIZE);
				}
				// If no unacked pkts and all file is read, finish loop
				if (unAckedPackets.isEmpty() && file.length() <= currentSeqNum - initialSeqNum) 
				{
					in.close();
					break;
				}
				
			//if times out re-send the oldest unAcked packet
			} 
			catch (SocketTimeoutException e) 
			{
				retransSegCount++;
				DatagramPacket resend = unAckedPackets.getFirst();
				resend.setAddress(receiverHostIP);
				resend.setPort(receiverPort);
				socket.send(resend);
				printPacketData("snd", resend);
		   	 }
		}	
	
		terminateConnection();	// Close connection

		// Print final lines to Sender_log.txt
		log.println("Original bytes transferred: " + bytesTransfCount);
		log.println("Data Segments Sent (excluding retransmissions): " + segsSentCount);
		log.println("Packets Dropped: " + droppedPktCount);
		log.println("Retransmitted Segments: " + retransSegCount);
		log.println("Duplicate Acknowledgements receieved: 0"); //will always be zero for this protocol
		
		log.close();// Close the log Writer
		timer.kill();// Stop the timer
	}
	
	private static void handShake() throws IOException 
	{
		currentSeqNum = r.nextInt(10000);// random number generator
		byte[] data = new byte[HEADER_SIZE];//initialize byte array for handshake pkts
		
		///--------------------SEND SYN----------------------
		data[0] = SYN;
		
		// Sequence and ACK nums
		System.arraycopy(int2ByteArray(currentSeqNum), 0, data, 1, 4);
		System.arraycopy(int2ByteArray(currentAckNum), 0, data, 5, 4);
		pktOut.setData(data, 0, data.length); // put data in pkt
		socket.send(pktOut);// Send pkt
		currentSeqNum += 1;//Increment Sequence num

		printPacketData("snd", pktOut);// print packet data to Sender_log.txt
		
		///--------------------RECEIVE SYN ACK-----------
		while (pktIn.getData()[0] != (SYN|ACK)) 
		{
			socket.receive(pktIn);
		}
		printPacketData("rcv", pktIn);

		// Update Sequence and ACK nums
		currentAckNum = getPacketSeqNum(pktIn)+1;
		currentSeqNum = getPacketAckNum(pktIn);	
		initialSeqNum = currentSeqNum;

		/////---------------------SEND ACK---------------
		data[0] = ACK; //ACK flag
		// Sequence and ack nums
		System.arraycopy(int2ByteArray(currentSeqNum), 0, data, 1, 4);
		System.arraycopy(int2ByteArray(currentAckNum), 0, data, 5, 4);
		pktOut.setData(data, 0, data.length); //data to pkt 
		socket.send(pktOut);// Send pkt
		printPacketData("snd", pktOut); // print pkt
		
	}
	

	private static void terminateConnection() throws IOException {
		
		// Prepare byte array for termination pkt
		byte[] data = new byte[HEADER_SIZE];
		
		// Send FIN
		data[0] = FIN;
		System.arraycopy(int2ByteArray(currentSeqNum), 0, data, 1, 4);
		System.arraycopy(int2ByteArray(currentAckNum), 0, data, 5, 4);
		pktOut.setData(data, 0, data.length);
		socket.send(pktOut);
		printPacketData("snd", pktOut);
		
		// Receive FIN ACK
		while (pktIn.getData()[0] != (FIN|ACK)) {
			socket.receive(pktIn);
		}
		printPacketData("rcv", pktIn);
		
		// Receive FIN
		while (pktIn.getData()[0] != (FIN)) {
			socket.receive(pktIn);
		}
		printPacketData("rcv", pktIn);
		
		// Send FIN ACK
		data[0] = (FIN|ACK);
		System.arraycopy(int2ByteArray(currentSeqNum), 0, data, 1, 4);
		System.arraycopy(int2ByteArray(currentAckNum), 0, data, 5, 4);
		pktOut.setData(data, 0, data.length);
		socket.send(pktOut);
		printPacketData("snd", pktOut);
		
		
		// close the socket
		socket.close();
	}
	
	// Helper method to translate an int to a byte array
	private static byte[] int2ByteArray( int num ) {
		byte[] result = new byte[4];
		result[0] = (byte) ((num & 0xFF000000) >> 24);
		result[1] = (byte) ((num & 0x00FF0000) >> 16);
		result[2] = (byte) ((num & 0x0000FF00) >> 8);
		result[3] = (byte) ((num & 0x000000FF) >> 0);
		return result;
	}
	
	// Helper method to get a packet's sequence number
	// Extracts bytes 1-4 of its data, and arranges it into an int
	private static int getPacketSeqNum(DatagramPacket p) {
		int result = ((p.getData()[1] & 0xFF) << 24)
			     | ((p.getData()[2] & 0xFF) << 16)
		             | ((p.getData()[3] & 0xFF) << 8)
		             | (p.getData()[4] & 0xFF);
		return result;
	}
	
	// Helper method to get a packet's acknowledgement number
	// Extracts bytes 5-8 of its data, and arranges it into an int
	private static int getPacketAckNum(DatagramPacket p) {
		int result = ((p.getData()[5] & 0xFF) << 24)
				| ((p.getData()[6] & 0xFF) << 16)
		                | ((p.getData()[7] & 0xFF) << 8)
		                | (p.getData()[8] & 0xFF);
		return result;
	}
	

	// Prints the packet's data to the sender log
	// Needs to know whether the packet is incoming, outgoing, or dropped
	private static void printPacketData(String packetStatus, DatagramPacket p) {
		log.print(packetStatus); //log is sender_log.txt
		log.print(" " + String.format("%6s", timer.getTimeElapsed()));
		
		//check type - print corresponding
		if (p.getData()[0] == 0) log.print("  D "); //Data 
		if (p.getData()[0] == ACK) log.print("  A "); //Acknowledge
		if (p.getData()[0] == SYN) log.print("  S "); //Syn
		if (p.getData()[0] == (SYN|ACK)) log.print(" SA "); //SynAcK
		if (p.getData()[0] == FIN) log.print("  F "); //Finish
		if (p.getData()[0] == (FIN|ACK)) log.print(" FA "); //FinACK 
		
		log.print(String.format("%5s", getPacketSeqNum(p)));
		log.print(" " + String.format("%4s",(p.getLength() - HEADER_SIZE)));
		log.println(" " + String.format("%5s", getPacketAckNum(p)));
	}
}
