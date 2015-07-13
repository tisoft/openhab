/**
 * Copyright (c) 2010-2014, openHAB.org and others.
 *
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 */
package org.openhab.binding.kwb.internal;

import gnu.io.CommPortIdentifier;
import gnu.io.PortInUseException;
import gnu.io.SerialPort;
import gnu.io.SerialPortEvent;
import gnu.io.SerialPortEventListener;
import gnu.io.UnsupportedCommOperationException;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PushbackInputStream;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.TooManyListenersException;

import org.apache.commons.io.IOUtils;
import org.openhab.core.events.EventPublisher;
import org.openhab.core.items.Item;
import org.openhab.core.library.types.DecimalType;
import org.openhab.core.library.types.OnOffType;
import org.openhab.core.library.types.StringType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * This class represents a serial device that is linked to exactly one String
 * item and/or Switch item.
 * 
 * @author Kai Kreuzer
 * 
 */
public class KWBDevice implements SerialPortEventListener {

	private static final Logger logger = LoggerFactory
			.getLogger(KWBDevice.class);

	public static class Register{
		public final int address;
		public final int register;
		public Register(int address, int register) {
			super();
			this.address = address;
			this.register = register;
		}
		@Override
		public int hashCode() {
			final int prime = 31;
			int result = 1;
			result = prime * result + address;
			result = prime * result + register;
			return result;
		}
		@Override
		public boolean equals(Object obj) {
			if (this == obj)
				return true;
			if (obj == null)
				return false;
			if (getClass() != obj.getClass())
				return false;
			Register other = (Register) obj;
			if (address != other.address)
				return false;
			if (register != other.register)
				return false;
			return true;
		}
		
		
	}
	
	private String port;
	private int baud = 19200;
	private Map<Register, String> itemMap = new HashMap<>();
	
	private EventPublisher eventPublisher;

	private CommPortIdentifier portId;
	private SerialPort serialPort;

	private InputStream inputStream;

	private ReceiveThread receiveThread;

	public KWBDevice(String port) {
		this.port = port;
	}

	public KWBDevice(String port, int baud) {
		this.port = port;
		this.baud = baud;
	}

	public void putItem(Register type, String itemName) {
		itemMap.put(type, itemName);
	}

	public void removeItem(String itemName) {
		itemMap.values().removeAll(Collections.singleton(itemName));
	}

	public String getPort() {
		return port;
	}

	public Map<Register, String> getItems() {
		return itemMap;
	}

	/**
	 * Initialize this device and open the serial port
	 * 
	 * @throws InitializationException
	 *             if port can not be opened
	 */
	@SuppressWarnings("rawtypes")
	public void initialize() throws InitializationException {
		// parse ports and if the default port is found, initialized the reader
		Enumeration portList = CommPortIdentifier.getPortIdentifiers();
		while (portList.hasMoreElements()) {
			CommPortIdentifier id = (CommPortIdentifier) portList.nextElement();
			if (id.getPortType() == CommPortIdentifier.PORT_SERIAL) {
				if (id.getName().equals(port)) {
					logger.debug("Serial port '{}' has been found.", port);
					portId = id;
				}
			}
		}
		if (portId != null) {
			// initialize serial port
			try {
				serialPort = (SerialPort) portId.open("openHAB", 2000);
			} catch (PortInUseException e) {
				throw new InitializationException(e);
			}

			try {
				inputStream = serialPort.getInputStream();
			} catch (IOException e) {
				throw new InitializationException(e);
			}

			try {
				serialPort.addEventListener(this);
			} catch (TooManyListenersException e) {
				throw new InitializationException(e);
			}

			// activate the DATA_AVAILABLE notifier
			serialPort.notifyOnDataAvailable(true);

			try {
				// set port parameters
				serialPort.setSerialPortParams(baud, SerialPort.DATABITS_8,
						SerialPort.STOPBITS_1, SerialPort.PARITY_NONE);
			} catch (UnsupportedCommOperationException e) {
				throw new InitializationException(e);
			}

			receiveThread = new ReceiveThread();

			receiveThread.start();
		} else {
			StringBuilder sb = new StringBuilder();
			portList = CommPortIdentifier.getPortIdentifiers();
			while (portList.hasMoreElements()) {
				CommPortIdentifier id = (CommPortIdentifier) portList
						.nextElement();
				if (id.getPortType() == CommPortIdentifier.PORT_SERIAL) {
					sb.append(id.getName() + "\n");
				}
			}
			throw new InitializationException("Serial port '" + port
					+ "' could not be found. Available ports are:\n"
					+ sb.toString());
		}
	}

	public void serialEvent(SerialPortEvent event) {
		switch (event.getEventType()) {
		case SerialPortEvent.BI:
		case SerialPortEvent.OE:
		case SerialPortEvent.FE:
		case SerialPortEvent.PE:
		case SerialPortEvent.CD:
		case SerialPortEvent.CTS:
		case SerialPortEvent.DSR:
		case SerialPortEvent.RI:
		case SerialPortEvent.OUTPUT_BUFFER_EMPTY:
			break;
		case SerialPortEvent.DATA_AVAILABLE:
			break;
		}
	}

	class ReceiveThread extends Thread {
		@Override
		public void run() {

			PushbackInputStream pbis = new PushbackInputStream(inputStream);
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

						logger.trace("Found packet start: {} {}", buf[0], buf[1]);

						if (buf[1] == 2) {
							// we have a sense message
							int lenght = pbis.read()&0xFF;
							logger.trace("Lenght: {}", lenght);

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

							int address=data[0]&0xFF;
							int messageCount=data[1]&0xFF;
							int checksum=data[data.length-1]&0xFF;
							
							//don't spam
							if(messageCount!=66)
								continue;
							
							logger.trace("Address: {} Message Count: {} Checksum: {}", new Integer[]{address, messageCount, checksum});
							
							//TODO bytes 2-6 are some bit fields
							
							//starting with byte 7 we have number values consisting of 2 bytes each
							//end is lenght-4 bytes
							List<DecimalType> values=new ArrayList<>();
							for (int i = 7; i < data.length-4; i += 2) {
								int first=data[i];//signed
								int second=data[i+1]&0xFF;//unsigned
				            	DecimalType value= new DecimalType(first*25.5f+second/10f);
				            	logger.trace("Value{}: {}",(i-7)/2, value);
				            	values.add(value);
				            }

							for(Entry<Register, String> e:itemMap.entrySet()){
								if(eventPublisher!=null&&e.getKey().address==address&&values.size()>e.getKey().register){
									eventPublisher.postUpdate(e.getValue(), values.get(e.getKey().register));
								}
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

	/**
	 * Close this serial device
	 */
	public void close() {
		if (receiveThread != null) {
			receiveThread.interrupt();
		}
		serialPort.removeEventListener();
		IOUtils.closeQuietly(inputStream);
		serialPort.close();
	}

	public void setEventPublisher(EventPublisher eventPublisher) {
		this.eventPublisher=eventPublisher;
	}
}
