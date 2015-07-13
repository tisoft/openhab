/**
 * Copyright (c) 2010-2014, openHAB.org and others.
 *
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 */
package org.openhab.binding.kwb.internal;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import org.openhab.binding.kwb.internal.KWBDevice.Register;
import org.openhab.core.events.AbstractEventSubscriber;
import org.openhab.core.events.EventPublisher;
import org.openhab.core.items.Item;
import org.openhab.core.library.items.NumberItem;
import org.openhab.core.library.items.StringItem;
import org.openhab.core.library.items.SwitchItem;
import org.openhab.core.library.types.StringType;
import org.openhab.core.types.Command;
import org.openhab.core.types.State;
import org.openhab.model.item.binding.BindingConfigParseException;
import org.openhab.model.item.binding.BindingConfigReader;

/**
 * <p>This class implements a binding of KWB heatings to openHAB.
 * The binding configurations are provided by the {@link GenericItemProvider}.</p>
 * 
 * <p>The format of the binding configuration is simple and looks like this:</p>
 * kwb="&lt;port&gt;","&lt;address&gt;","&lt;register&gt;" where &lt;port&gt; is the identification of the serial port on the host system, e.g.
 * "COM1" on Windows, "/dev/ttyS0" on Linux or "/dev/tty.PL2303-0000103D" on Mac and "&lt;parameter&gt;" is the data point to be extracted.
 * 
 * @author Markus Heberling
 *
 */
public class KWBBinding extends AbstractEventSubscriber implements BindingConfigReader {

	private Map<String, KWBDevice> serialDevices = new HashMap<String, KWBDevice>();

	/** stores information about the which items are associated to which port. The map has this content structure: itemname -> port */ 
	private Map<Item, String> itemMap = new HashMap<Item, String>();
	
	private EventPublisher eventPublisher = null;
	
	public void setEventPublisher(EventPublisher eventPublisher) {
		this.eventPublisher = eventPublisher;
		
		for(KWBDevice serialDevice : serialDevices.values()) {
			serialDevice.setEventPublisher(eventPublisher);
		}
	}
	/** stores information about the context of items. The map has this content structure: context -> Set of items */ 
	private Map<String, Set<Item>> contextMap = new HashMap<String, Set<Item>>();
	
	public void unsetEventPublisher(EventPublisher eventPublisher) {
		this.eventPublisher = null;

		for(KWBDevice serialDevice : serialDevices.values()) {
			serialDevice.setEventPublisher(null);
		}
	}
	/**
	 * {@inheritDoc}
	 */
	public String getBindingType() {
		return "kwb";
	}

	/**
	 * {@inheritDoc}
	 */
	public void validateItemType(Item item, String bindingConfig) throws BindingConfigParseException {
		if (!(item instanceof SwitchItem || item instanceof NumberItem)) {
			throw new BindingConfigParseException("item '" + item.getName()
					+ "' is of type '" + item.getClass().getSimpleName()
					+ "', only Switch- and NumberItems are allowed - please check your *.items configuration");
		}
	}
	
	/**
	 * {@inheritDoc}
	 */
	public void processBindingConfiguration(String context, Item item, String bindingConfig) throws BindingConfigParseException {
		String port = bindingConfig.split(",", 3)[0];
		int address=Integer.valueOf(bindingConfig.split(",", 3)[1]);
		int register=Integer.valueOf(bindingConfig.split(",", 3)[2]);
		KWBDevice serialDevice = serialDevices.get(port);
		if (serialDevice == null) {
			serialDevice = new KWBDevice(port);
			serialDevice.setEventPublisher(eventPublisher);
			try {
				serialDevice.initialize();
			} catch (InitializationException e) {
				throw new BindingConfigParseException(
						"Could not open serial port " + port + ": "
								+ e.getMessage());
			} catch (Throwable e) {
				throw new BindingConfigParseException(
						"Could not open serial port " + port + ": "
								+ e.getMessage());
			}
			itemMap.put(item, port);
			serialDevices.put(port, serialDevice);
		}
		
		serialDevice.putItem(new Register(address, register), item.getName());
		
		Set<Item> itemNames = contextMap.get(context);
		if (itemNames == null) {
			itemNames = new HashSet<Item>();
			contextMap.put(context, itemNames);
		}
		itemNames.add(item);
	}

	/**
	 * {@inheritDoc}
	 */
	public void removeConfigurations(String context) {
		Set<Item> itemNames = contextMap.get(context);
		if(itemNames!=null) {
			for(Item itemName : itemNames) {
				// we remove all information in the serial devices
				KWBDevice serialDevice = serialDevices.get(itemMap.get(itemName));
				itemMap.remove(itemName);
				if(serialDevice==null) {
					continue;
				}
				serialDevice.removeItem(itemName.getName());
				// if there is no binding left, dispose this device
				if(serialDevice.getItems().isEmpty()) {
					serialDevice.close();
					serialDevices.remove(serialDevice.getPort());
				}
			}
			contextMap.remove(context);
		}
	}

}
