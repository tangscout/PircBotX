/**
 * Copyright (C) 2010-2013 Leon Blakey <lord.quackstar at gmail.com>
 *
 * This file is part of PircBotX.
 *
 * PircBotX is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * PircBotX is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with PircBotX. If not, see <http://www.gnu.org/licenses/>.
 */
package org.pircbotx;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.InterruptedIOException;
import java.io.OutputStreamWriter;
import java.lang.ref.WeakReference;
import java.net.InetAddress;
import java.net.Socket;
import java.net.SocketException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.pircbotx.cap.EnableCapHandler;
import org.pircbotx.dcc.DccHandler;
import org.pircbotx.exception.IrcException;
import org.pircbotx.hooks.CoreHooks;
import org.pircbotx.hooks.ListenerAdapter;
import org.pircbotx.hooks.events.*;
import org.pircbotx.hooks.managers.ThreadedListenerManager;
import org.pircbotx.output.OutputCAP;
import org.pircbotx.output.OutputDCC;
import org.pircbotx.output.OutputIRC;
import org.pircbotx.output.OutputRaw;
import org.slf4j.Marker;
import org.slf4j.MarkerFactory;

/**
 * PircBotX is a Java framework for writing IRC bots quickly and easily.
 * <p>
 * It provides an event-driven architecture to handle common IRC
 * events, flood protection, DCC support, ident support, and more.
 * The comprehensive logfile format is suitable for use with pisg to generate
 * channel statistics.
 * <p>
 * Methods of the PircBotX class can be called to send events to the IRC server
 * that it connects to. For example, calling the sendMessage method will
 * send a message to a channel or user on the IRC server. Multiple servers
 * can be supported using multiple instances of PircBotX.
 * <p>
 * To perform an action when the PircBotX receives a normal message from the IRC
 * server, you would listen for the MessageEvent in your listener (see {@link ListenerAdapter}).
 * Many other events are dispatched as well for other incoming lines
 *
 * @author Origionally by:
 * <a href="http://www.jibble.org/">Paul James Mutton</a> for <a href="http://www.jibble.org/pircbot.php">PircBot</a>
 * <p>Forked and Maintained by Leon Blakey <lord.quackstar at gmail.com> in <a href="http://pircbotx.googlecode.com">PircBotX</a>
 */
@RequiredArgsConstructor
@Slf4j
public class PircBotX {
	/**
	 * The definitive version number of this release of PircBotX.
	 */
	//THIS LINE IS AUTOGENERATED, DO NOT EDIT
	public static final String VERSION = "2.0-SNAPSHOT";
	protected static final AtomicInteger BOT_COUNT = new AtomicInteger();
	@Getter
	protected final int botId;
	//Utility objects
	@Getter
	protected final Configuration configuration;
	@Getter
	protected final InputParser inputParser;
	@Getter
	protected final UserChannelDao<User, Channel> userChannelDao;
	@Getter
	protected final DccHandler dccHandler;
	protected final ServerInfo serverInfo;
	//Connection stuff.
	@Getter(AccessLevel.PROTECTED)
	protected Socket socket;
	protected BufferedReader inputReader;
	protected OutputStreamWriter outputWriter;
	protected final OutputRaw outputRaw;
	protected final OutputIRC outputIRC;
	protected final OutputCAP outputCAP;
	protected final OutputDCC outputDCC;
	@Getter
	protected List<String> enabledCapabilities = new ArrayList();
	protected String nick = "";
	protected boolean loggedIn = false;
	protected Thread shutdownHook;
	@Getter
	@Setter
	protected boolean autoReconnect;
	@Getter
	@Setter
	protected boolean autoReconnectChannels;
	protected boolean shutdownCalled = false;
	protected final Object shutdownCalledLock = new Object();

	/**
	 * Constructs a PircBotX with the default settings and 
	 * <ul><li>Add {@link CoreHooks} to the default ListenerManager, {@link ThreadedListenerManager}</li>
	 * <li>Add a shutdown hook (See {@link #useShutdownHook(boolean) })</li>
	 * <li>Add an {@link EnableCapHandler} to enable multi-prefix, ignoring it if it fails</li>
	 * <li>Set {@link #getSocketTimeout() default socket timeout} to 5 minutes</li>
	 * <li>Set {@link #getMessageDelay() message delay} to 1 second</li>
	 * <li>Turn off {@link #isAutoNickChange() auto nick changing}</li>
	 * <li>Turn off {@link #isVerbose() verbose} logging</li>
	 * <li>Turn off {@link #isCapEnabled() () CAP handling}</li>
	 * </ul>
	 */
	public PircBotX(Configuration configuration) {
		botId = BOT_COUNT.getAndIncrement();
		this.configuration = configuration;
		this.userChannelDao = configuration.getBotFactory().createUserChannelDao(this);
		this.serverInfo = configuration.getBotFactory().createServerInfo(this);
		this.outputRaw = configuration.getBotFactory().createOutputRaw(this);
		this.outputIRC = configuration.getBotFactory().createOutputIRC(this);
		this.outputCAP = configuration.getBotFactory().createOutputCAP(this);
		this.outputDCC = configuration.getBotFactory().createOutputDCC(this);
		this.dccHandler = configuration.getBotFactory().createDccHandler(this);
		this.inputParser = configuration.getBotFactory().createInputParser(this);
	}

	/**
	 * Attempt to connect to the specified IRC server using the supplied
	 * port number, password, and socketFactory. On success a {@link ConnectEvent}
	 * will be dispatched
	 *
	 * @param hostname The hostname of the server to connect to.
	 * @param port The port number to connect to on the server.
	 * @param password The password to use to join the server.
	 * @param socketFactory The factory to use for creating sockets, including secure sockets
	 *
	 * @throws IOException if it was not possible to connect to the server.
	 * @throws IrcException if the server would not let us join it.
	 * @throws NickAlreadyInUseException if our nick is already in use on the server.
	 */
	public void connect() throws IOException, IrcException {
		Utils.addBotToMDC(this);
		if (isConnected())
			throw new IrcException(IrcException.Reason.AlreadyConnected, "Must disconnect from server before connecting again");
		synchronized (shutdownCalledLock) {
			if (shutdownCalled)
				throw new RuntimeException("Shutdown has not been called but your still connected. This shouldn't happen");
			shutdownCalled = false;
		}
		if (configuration.isIdentServerEnabled() && IdentServer.getServer() == null)
			throw new RuntimeException("UseIdentServer is enabled but no IdentServer has been started");

		//Reset capabilities
		enabledCapabilities = new ArrayList();

		// Connect to the server by DNS server
		for (InetAddress curAddress : InetAddress.getAllByName(configuration.getServerHostname())) {
			log.debug("Trying address " + curAddress);
			try {
				socket = configuration.getSocketFactory().createSocket(curAddress, configuration.getServerPort(), configuration.getLocalAddress(), 0);

				//No exception, assume successful
				break;
			} catch (Exception e) {
				log.debug("Unable to connect to " + configuration.getServerHostname() + " using the IP address " + curAddress.getHostAddress() + ", trying to check another address.", e);
			}
		}

		//Make sure were connected
		if (socket == null || (socket != null && !socket.isConnected()))
			throw new IOException("Unable to connect to the IRC network " + configuration.getServerHostname() + " (last connection attempt exception attached)");
		log.info("Connected to server.");

		changeSocket(socket);
		configuration.getListenerManager().dispatchEvent(new SocketConnectEvent(this));

		if (configuration.isIdentServerEnabled())
			IdentServer.getServer().addIdentEntry(socket.getInetAddress(), socket.getPort(), socket.getLocalPort(), configuration.getLogin());

		if (configuration.isCapEnabled())
			// Attempt to initiate a CAP transaction.
			sendCAP().getSupported();

		// Attempt to join the server.
		if (configuration.isWebIrcEnabled())
			sendRaw().rawLineNow("WEBIRC " + configuration.getWebIrcPassword()
					+ " " + configuration.getWebIrcUsername()
					+ " " + configuration.getWebIrcHostname()
					+ " " + configuration.getWebIrcAddress().getHostAddress());
		if (!StringUtils.isBlank(configuration.getServerPassword()))
			sendRaw().rawLineNow("PASS " + configuration.getServerPassword());

		sendRaw().rawLineNow("NICK " + configuration.getName());
		sendRaw().rawLineNow("USER " + configuration.getLogin() + " 8 * :" + configuration.getVersion());

		//Start input to start accepting lines
		startLineProcessing();
	}

	protected void changeSocket(Socket socket) throws IOException {
		this.socket = socket;
		this.inputReader = new BufferedReader(new InputStreamReader(socket.getInputStream(), configuration.getEncoding()));
		this.outputWriter = new OutputStreamWriter(socket.getOutputStream(), configuration.getEncoding());
	}

	protected void startLineProcessing() {
		while (true) {
			//Get line from the server
			String line;
			try {
				line = inputReader.readLine();
			} catch (InterruptedIOException iioe) {
				// This will happen if we haven't received anything from the server for a while.
				// So we shall send it a ping to check that we are still connected.
				sendRaw().rawLine("PING " + (System.currentTimeMillis() / 1000));
				// Now we go back to listening for stuff from the server...
				continue;
			} catch (Exception e) {
				if (e instanceof SocketException && isShutdownCalled()) {
					log.info("Shutdown has been called, closing InputParser");
					return;
				} else {
					//Something is wrong. Assume its bad and begin disconnect
					log.error("Exception encountered when reading next line from server", e);
					line = null;
				}
			}

			//End the loop if the line is null
			if (line == null)
				break;

			//Start acting the line
			try {
				inputParser.handleLine(line);
			} catch (Exception e) {
				//Exception in client code. Just log and continue
				log.error("Exception encountered when parsing line", e);
			}

			//Do nothing if this thread is being interrupted (meaning shutdown() was run)
			if (Thread.interrupted())
				return;
		}

		//Now that the socket is definatly closed call event, log, and kill the OutputThread
		shutdown();
	}

	/**
	 * Actually sends the raw line to the server. This method is NOT SYNCHRONIZED 
	 * since it's only called from methods that handle locking
	 * @param line 
	 */
	protected void sendRawLineToServer(String line) {
		if (line.length() > configuration.getMaxLineLength() - 2)
			line = line.substring(0, configuration.getMaxLineLength() - 2);
		try {
			outputWriter.write(line + "\r\n");
			outputWriter.flush();
		} catch (Exception e) {
			//Not much else we can do, but this requires attention of whatever is calling this
			throw new RuntimeException("Exception encountered when writing to socket", e);
		}
	}

	protected void loggedIn(String nick) {
		this.loggedIn = true;
		setNick(nick);

		if (configuration.isShutdownHookEnabled())
			Runtime.getRuntime().addShutdownHook(shutdownHook = new PircBotX.BotShutdownHook(this));
	}

	/**
	 * Reconnects to the IRC server that we were previously connected to using 
	 * the same {@link Configuration}
	 * This method will throw an IrcException if we have never connected
	 * to an IRC server previously.
	 *
	 * @since PircBot 0.9.9
	 *
	 * @throws IOException if it was not possible to connect to the server.
	 * @throws IrcException if the server would not let us join it.
	 * @throws NickAlreadyInUseException if our nick is already in use on the server.
	 */
	public synchronized void reconnect() throws IOException, IrcException {
		if (configuration == null)
			throw new IrcException(IrcException.Reason.ReconnectBeforeConnect, "Must connect to the server before reconnecting");
		try {
			connect();
		} catch (IOException e) {
			configuration.getListenerManager().dispatchEvent(new ReconnectEvent(this, false, e));
			throw e;
		} catch (IrcException e) {
			configuration.getListenerManager().dispatchEvent(new ReconnectEvent(this, false, e));
			throw e;
		} catch (RuntimeException e) {
			configuration.getListenerManager().dispatchEvent(new ReconnectEvent(this, false, e));
			throw e;
		}
		//Should be good
		configuration.getListenerManager().dispatchEvent(new ReconnectEvent(this, true, null));
	}

	public OutputRaw sendRaw() {
		return outputRaw;
	}

	public OutputIRC sendIRC() {
		return outputIRC;
	}

	public OutputCAP sendCAP() {
		return outputCAP;
	}

	public OutputDCC sendDCC() {
		return outputDCC;
	}

	/**
	 * Sets the internal nick of the bot. This is only to be called by the
	 * PircBotX class in response to notification of nick changes that apply
	 * to us.
	 *
	 * @param nick The new nick.
	 */
	protected void setNick(String nick) {
		synchronized (userChannelDao.accessLock) {
			User user = userChannelDao.getUser(this.nick);
			userChannelDao.renameUser(user, nick);
			this.nick = nick;
		}
	}

	/**
	 * Returns the current nick of the bot. Note that if you have just changed
	 * your nick, this method will still return the old nick until confirmation
	 * of the nick change is received from the server.
	 *
	 * @since PircBot 1.0.0
	 *
	 * @return The current nick of the bot.
	 */
	public String getNick() {
		return nick;
	}

	/**
	 * Returns whether or not the PircBotX is currently connected to a server.
	 * The result of this method should only act as a rough guide,
	 * as the result may not be valid by the time you act upon it.
	 *
	 * @return True if and only if the PircBotX is currently connected to a server.
	 */
	public boolean isConnected() {
		return socket != null && !socket.isClosed();
	}

	/**
	 * Returns a String representation of this object.
	 * You may find this useful for debugging purposes, particularly
	 * if you are using more than one PircBotX instance to achieve
	 * multiple server connectivity. The format of
	 * this String may change between different versions of PircBotX
	 * but is currently something of the form
	 * <code>
	 *   Version{PircBotX x.y.z Java IRC Bot - www.jibble.org}
	 *   Connected{true}
	 *   Server{irc.dal.net}
	 *   Port{6667}
	 *   Password{}
	 * </code>
	 *
	 * @since PircBot 0.9.10
	 *
	 * @return a String representation of this object.
	 */
	@Override
	public String toString() {
		return "Version{" + configuration.getVersion() + "}"
				+ " Connected{" + isConnected() + "}"
				+ " Server{" + configuration.getServerHostname() + "}"
				+ " Port{" + configuration.getServerPort() + "}"
				+ " Password{" + configuration.getServerPassword() + "}";
	}

	/**
	 * Gets the bots own user object
	 * @return The user object representing this bot
	 */
	public User getUserBot() {
		return userChannelDao.getUser(getNick());
	}

	/**
	 * @return the serverInfo
	 */
	public ServerInfo getServerInfo() {
		return serverInfo;
	}

	public InetAddress getLocalAddress() {
		return socket.getLocalAddress();
	}

	protected boolean isShutdownCalled() {
		synchronized (shutdownCalledLock) {
			return shutdownCalled;
		}
	}

	/**
	 * Calls shutdown allowing reconnect
	 */
	public void shutdown() {
		shutdown(false);
	}

	/**
	 * Fully shutdown the bot and all internal resources. This will close the
	 * connections to the server, kill background threads, clear server specific
	 * state, and dispatch a DisconnectedEvent
	 * <p/>
	 * @param noReconnect Toggle whether to reconnect if enabled. Set to true to
	 * 100% shutdown the bot
	 */
	public void shutdown(boolean noReconnect) {
		//Guard against multiple calls
		if (shutdownCalled)
			synchronized (shutdownCalledLock) {
				if (shutdownCalled)
					throw new RuntimeException("Shutdown has already been called");
			}
		shutdownCalled = true;

		try {
			socket.close();
		} catch (Exception e) {
			log.error("Can't close socket", e);
		}

		//Close the socket from here and let the threads die
		if (socket != null && !socket.isClosed())
			try {
				socket.close();
			} catch (Exception e) {
				log.error("Cannot close socket", e);
			}

		//Cache channels for possible next reconnect
		Map<String, String> previousChannels = new HashMap();
		for (Channel curChannel : userChannelDao.getAllChannels()) {
			String key = (curChannel.getChannelKey() == null) ? "" : curChannel.getChannelKey();
			previousChannels.put(curChannel.getName(), key);
		}

		//Dispatch event
		if (autoReconnect && !noReconnect)
			try {
				reconnect();
				if (autoReconnectChannels)
					for (Map.Entry<String, String> curEntry : previousChannels.entrySet())
						sendIRC().joinChannel(curEntry.getKey(), curEntry.getValue());
			} catch (Exception e) {
				//Not much we can do with it
				throw new RuntimeException("Can't reconnect to server", e);
			}
		else {
			configuration.getListenerManager().dispatchEvent(new DisconnectEvent(this, userChannelDao.createSnapshot()));
			log.debug("Disconnected.");
		}

		//Shutdown listener manager
		configuration.getListenerManager().shutdown(this);

		//Clear relevant variables of information
		userChannelDao.close();
		inputParser.close();
		dccHandler.close();
	}

	protected static class BotShutdownHook extends Thread {
		protected final WeakReference<PircBotX> thisBotRef;

		public BotShutdownHook(PircBotX bot) {
			this.thisBotRef = new WeakReference(bot);
			setName("bot" + BOT_COUNT + "-shutdownhook");
		}

		@Override
		public void run() {
			PircBotX thisBot = thisBotRef.get();
			if (thisBot != null && thisBot.isConnected() && thisBot.socket != null && !thisBot.socket.isClosed())
				try {
					thisBot.sendIRC().quitServer();
				} finally {
					thisBot.shutdown(true);
				}
		}
	}
}
