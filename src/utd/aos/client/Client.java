package utd.aos.client;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import utd.aos.utils.Message;
import utd.aos.utils.Operations;
import utd.aos.utils.Resource;
import utd.aos.utils.Operations.OperationMethod;

public class Client {
	public Message request(Operations operation, ObjectOutputStream o_out, ObjectInputStream o_in) throws IOException, ClassNotFoundException {	
		//creating the request
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
		
		//sends the operation resquest
		o_out.writeObject(operation);
		
		//wait for the response
		Object object = o_in.readObject();
		
		
		Message m;
		
		if (object instanceof Message) {
			 m = (Message)object;
			 return m;
		} else {
			return null;
		}
	}
}
