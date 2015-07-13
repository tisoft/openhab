package org.openhab.binding.kwb.internal;

import java.io.IOException;
import java.io.PushbackInputStream;

import org.openhab.core.library.types.DecimalType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class KWBParser {

	private static final Logger logger = LoggerFactory
			.getLogger(KWBParser.class);

	public static void main(String[] args) {
		PushbackInputStream pbis = new PushbackInputStream(
				KWBParser.class.getResourceAsStream("/kwb.dump"), 2);
		try {
			do {
				outer: {
					// new packets start with 0x02 followed by something other
					// then
					// 0x00
					byte buf[] = new byte[2];
					while (true) {
						// read 2 bytes
						pbis.read(buf);

						if (buf[0] == 0x02 && buf[1] != 0x00)
							break;
						// pushback the last byte, so that we read it as first
						// byte the next time
						pbis.unread(buf, 1, 1);
					}
					// we found a new packet start

					logger.debug("Found packet start: {} {}", buf[0], buf[1]);

					if (buf[1] == 2) {
						// we have a sense message
						int lenght = pbis.read();
						logger.debug("Lenght: {}", lenght);

						//lenght includes already read header bytes, so data array needs to be a bit smaller
						byte data[] = new byte[lenght-2];

						for (int i = 0; i < data.length; i++) {
							// we can't just copy the data form the stream
							// we need to replace all 0x0200 with 0x00
							byte b = (byte) pbis.read();
							if (b == 0x02) {
								byte b2 = (byte) pbis.read();
								if (b2 != 0x00) {
									logger.warn(
											"Found Package header {} {} inside package. Skipping to next package.",
											b, b2);
									//pbis.unread(new byte[]{b, b2});
									break outer;
								}
								logger.trace(
										"replaced 0x0200 with 0x02");
							}
							data[i] = b;
						}

						int address=data[0];
						int messageCount=data[1];
						int checksum=data[data.length-1];
						
						logger.debug("Address: {} Message Count: {} Checksum: {}", new Integer[]{address, messageCount, checksum});
						
						//TODO bytes 2-6 are some bit fields
						
						//starting with byte 7 we have number values consisting of 2 bytes each
						//end is lenght-4 bytes
						for (int i = 7; i < data.length-4; i += 2) {
							int first=data[i];//signed
							int second=data[i+1]&0xFF;//unsigned
			            	DecimalType value= new DecimalType(first*25.5f+second/10f);
			            	logger.debug("Value{}: {}",(i-7)/2, value);
			            }

						
					} else {
						// ignore other messages for now
					}
					try {
						Thread.sleep(10);
					} catch (InterruptedException e) {
						logger.info("KWB Receive Thread interrupted.", e);
					}
				}
			} while (!Thread.interrupted());
		} catch (IOException e) {
			throw new RuntimeException(e);
		}

	}
}
