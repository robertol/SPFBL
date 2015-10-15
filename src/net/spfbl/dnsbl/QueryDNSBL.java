/*
 * This file is part of SPFBL.
 * 
 * SPFBL is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 * 
 * SPFBL is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 * 
 * You should have received a copy of the GNU General Public License
 * along with SPFBL.  If not, see <http://www.gnu.org/licenses/>.
 */
package net.spfbl.dnsbl;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import net.spfbl.core.Server;
import net.spfbl.spf.SPF;
import net.spfbl.whois.SubnetIPv4;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketException;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.TreeSet;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import net.spfbl.whois.Domain;
import org.apache.commons.lang3.SerializationUtils;
import org.xbill.DNS.ARecord;
import org.xbill.DNS.DClass;
import org.xbill.DNS.Flags;
import org.xbill.DNS.Header;
import org.xbill.DNS.Message;
import org.xbill.DNS.Name;
import org.xbill.DNS.Rcode;
import org.xbill.DNS.Record;
import org.xbill.DNS.Section;
import org.xbill.DNS.TXTRecord;

/**
 * Servidor de consulta DNSBL.
 * 
 * @author Leandro Carlos Rodrigues <leandro@spfbl.net>
 */
public final class QueryDNSBL extends Server {

    private final int PORT = 53;
    private final DatagramSocket SERVER_SOCKET;
    
    /**
     * Mapa para cache dos registros DNS consultados.
     */
    private static final HashMap<String,InetAddress> MAP = new HashMap<String,InetAddress>();
    /**
     * Flag que indica se o cache foi modificado.
     */
    private static boolean CHANGED = false;
    
    private static synchronized boolean dropExact(String token) {
        InetAddress ret = MAP.remove(token);
        if (ret == null) {
            return false;
        } else {
            CHANGED = true;
            return true;
        }
    }

    private static synchronized boolean putExact(String key, InetAddress value) {
        InetAddress ret = MAP.put(key, value);
        if (value.equals(ret)) {
            return false;
        } else {
            CHANGED = true;
            return true;
        }
    }

    private static synchronized TreeSet<String> keySet() {
        TreeSet<String> keySet = new TreeSet<String>();
        keySet.addAll(MAP.keySet());
        return keySet;
    }

    public static synchronized HashMap<String,InetAddress> getMap() {
        HashMap<String,InetAddress> map = new HashMap<String,InetAddress>();
        map.putAll(MAP);
        return map;
    }

    private static synchronized boolean containsExact(String address) {
        return MAP.containsKey(address);
    }

    private static synchronized InetAddress getExact(String host) {
        return MAP.get(host);
    }

    private static synchronized Collection<InetAddress> getValues() {
        return MAP.values();
    }

    private static synchronized boolean isChanged() {
        return CHANGED;
    }

    private static synchronized void setStored() {
        CHANGED = true;
    }
    
    /**
     * Adiciona um registro DNS no mapa de cache.
     */
    public static boolean add(String hostname, InetAddress address) {
        if (hostname == null || address == null) {
            return false;
        } else if (Domain.isHostname(hostname)) {
            hostname = Domain.normalizeHostname(hostname, true);
            return putExact(hostname, address) ;
        } else {
            return false;
        }
    }
    
    private static InetAddress get(String hostname) {
        if (hostname == null) {
            return null;
        } else if (Domain.isHostname(hostname)) {
            hostname = Domain.normalizeHostname(hostname, true);
            return getExact(hostname);
        } else {
            return null;
        }
    }
    
    public static boolean drop(String hostname) {
        if (hostname == null) {
            return false;
        } else if (Domain.isHostname(hostname)) {
            hostname = Domain.normalizeHostname(hostname, true);
            return dropExact(hostname);
        } else {
            return false;
        }
    }
    
    public static void store() {
        if (isChanged()) {
            try {
                long time = System.currentTimeMillis();
                File file = new File("./data/dns.map");
                FileOutputStream outputStream = new FileOutputStream(file);
                try {
                    SerializationUtils.serialize(getMap(), outputStream);
                    setStored();
                } finally {
                    outputStream.close();
                }
                Server.logStore(time, file);
            } catch (Exception ex) {
                Server.logError(ex);
            }
        }
    }

    public static void load() {
        long time = System.currentTimeMillis();
        File file = new File("./data/dns.map");
        if (file.exists()) {
            try {
                HashMap<String,InetAddress> map;
                FileInputStream fileInputStream = new FileInputStream(file);
                try {
                    map = SerializationUtils.deserialize(fileInputStream);
                } finally {
                    fileInputStream.close();
                }
                for (String key : map.keySet()) {
                    InetAddress value = map.get(key);
                    putExact(key, value);
                }
                Server.logLoad(time, file);
            } catch (Exception ex) {
                Server.logError(ex);
            }
        }
    }
    
    /**
     * Configuração e intanciamento do servidor.
     * @throws java.net.SocketException se houver falha durante o bind.
     */
    public QueryDNSBL() throws SocketException {
        super("ServerDNSBL");
        // Criando conexões.
        Server.logDebug("Binding DNSBL socket on port " + PORT + "...");
        SERVER_SOCKET = new DatagramSocket(PORT);
    }
    
    /**
     * Representa uma conexão ativa.
     * Serve para processar todas as requisições.
     */
    private class Connection extends Thread {
        
        /**
         * O poll de pacotes de consulta a serem processados.
         */
        private final LinkedList<DatagramPacket> PACKET_LIST = new LinkedList<DatagramPacket>();
        
        /**
         * Semáforo que controla o pool de pacotes.
         */
        private final Semaphore PACKET_SEMAPHORE = new Semaphore(0);
        
        public Connection() {
            super("DNSBL" + (CONNECTION_COUNT+1));
            // Toda connexão recebe prioridade mínima.
            setPriority(Thread.MIN_PRIORITY);
        }
        
        /**
         * Processa um pacote de consulta.
         * @param packet o pacote de consulta a ser processado.
         */
        private synchronized void process(DatagramPacket packet) {
            PACKET_LIST.offer(packet);
            if (isAlive()) {
                // Libera o próximo processamento.
                PACKET_SEMAPHORE.release();
            } else {
                // Inicia a thread pela primmeira vez.
                start();
            }
        }
        
        /**
         * Fecha esta conexão liberando a thread.
         */
        private void close() {
            Server.logDebug("Closing " + getName() + "...");
            PACKET_SEMAPHORE.release();
        }
        
        /**
         * Aguarda nova chamada.
         */
        private void waitCall() {
            try {
                PACKET_SEMAPHORE.acquire();
            } catch (InterruptedException ex) {
                Server.logError(ex);
            }
        }
        
        /**
         * Processamento da consulta e envio do resultado.
         * Aproveita a thead para realizar procedimentos em background.
         */
        @Override
        public void run() {
            while (!PACKET_LIST.isEmpty()) {
                try {
                    DatagramPacket packet = PACKET_LIST.poll();
                    long time = System.currentTimeMillis();
                    byte[] data = packet.getData();
                    String result;
                    // Processando consulta DNS.
                    Message message = new Message(data);
                    Header header = message.getHeader();
                    Record question = message.getQuestion();
                    Name name = question.getName();
                    String query = name.toString();
                    long ttl = 1440; // Tempo de cache no DNS de um dia.
                    int index = query.lastIndexOf(".dnsbl.");
                    boolean listed = false;
                    if (index > 0) {
                        String token = query.substring(0, index);
                        if (SubnetIPv4.isValidIPv4(token)) {
                            // A consulta é um IPv4.
                            // Reverter ordem dos octetos.
                            byte[] address = SubnetIPv4.split(token);
                            byte octeto = address[0];
                            String ip = Integer.toString((int) octeto & 0xFF);
                            for (int i = 1; i < address.length; i++) {
                                octeto = address[i];
                                ip = ((int) octeto & 0xFF) + "." + ip;
                            }
                            ip = SubnetIPv4.normalizeIPv4(ip);
                            if (SPF.isBlacklisted(ip)) {
                                listed = true;
                                ttl = SPF.getComplainTTL(ip);
                                token = "IP " + ip;
                            }
                        } else {
                            listed = false;
                            token = null;

                        }
                        // Alterando mensagem DNS para resposta.
                        header.setFlag(Flags.QR);
                        header.setFlag(Flags.AA);
                        if (listed) {
                            // Está listado.
                            result = "127.0.0.2";
                            String txtMessage = token + " is listed in this server.";
                            InetAddress resultAddress = InetAddress.getByName(result);
                            ARecord anwser = new ARecord(name, DClass.IN, ttl, resultAddress);
                            TXTRecord txt = new TXTRecord(name, DClass.IN, ttl, txtMessage);
                            message.addRecord(anwser, Section.ANSWER);
                            message.addRecord(txt, Section.ANSWER);
                            result += " " + txtMessage;
                        } else {
                            // Não está listado.
                            result = "NXDOMAIN";
                            header.setRcode(Rcode.NXDOMAIN);
                        }
                    } else {
                        InetAddress resultAddress = get(query);
                        if (resultAddress == null) {
                            // Não está mapeado.
                            result = "NXDOMAIN";
                            header.setRcode(Rcode.NXDOMAIN);
                        } else {
                            ARecord anwser = new ARecord(name, DClass.IN, ttl, resultAddress);
                            message.addRecord(anwser, Section.ANSWER);
                            result = resultAddress.getHostAddress();
                        }
                    }
                    // Enviando resposta.
                    InetAddress ipAddress = packet.getAddress();
                    int portDestiny = packet.getPort();
                    byte[] sendData = message.toWire();
                    DatagramPacket sendPacket = new DatagramPacket(
                            sendData, sendData.length,
                            ipAddress, portDestiny
                            );
                    SERVER_SOCKET.send(sendPacket);
                    // Log da consulta com o respectivo resultado.
                    Server.logQueryDNSBL(time, ipAddress, query, result);
                } catch (Exception ex) {
                    Server.logError(ex);
                } finally {
                    // Oferece a conexão ociosa na última posição da lista.
                    CONNECTION_POLL.offer(this);
                    CONNECION_SEMAPHORE.release();
                    // Aguarda nova chamada.
                    waitCall();
                }
            }
        }
    }
    
    /**
     * Pool de conexões ativas.
     */
    private final LinkedList<Connection> CONNECTION_POLL = new LinkedList<Connection>();
    
    /**
     * Semáforo que controla o pool de conexões.
     */
    private final Semaphore CONNECION_SEMAPHORE = new Semaphore(0);
    
    /**
     * Quantidade total de conexões intanciadas.
     */
    private int CONNECTION_COUNT = 0;
    
    private static final int CONNECTION_LIMIT = 10;
    
    /**
     * Coleta uma conexão ociosa ou inicia uma nova.
     * @return uma conexão ociosa ou nova se não houver ociosa.
     */
    private Connection pollConnection() {
        try {
            if (CONNECION_SEMAPHORE.tryAcquire(100, TimeUnit.MILLISECONDS)) {
                return CONNECTION_POLL.poll();
            } else if (CONNECTION_COUNT < CONNECTION_LIMIT) {
                // Cria uma nova conexão se não houver conecxões ociosas.
                // O servidor aumenta a capacidade conforme a demanda.
                Server.logDebug("Creating DNSBL" + (CONNECTION_COUNT + 1) + "...");
                Connection connection = new Connection();
                CONNECTION_COUNT++;
                return connection;
            } else if (CONNECION_SEMAPHORE.tryAcquire(1, TimeUnit.SECONDS)) {
                return CONNECTION_POLL.poll();
            } else {
                return null;
            }
        } catch (InterruptedException ex) {
            return null;
        }
    }
    
    /**
     * Inicialização do serviço.
     */
    @Override
    public synchronized void run() {
        try {
            Server.logDebug("Listening DNSBL on UDP port " + PORT + "...");
            while (continueListenning()) {
                try {
                    byte[] receiveData = new byte[1024];
                    DatagramPacket packet = new DatagramPacket(
                            receiveData, receiveData.length);
                    SERVER_SOCKET.receive(packet);
                    Connection connection = pollConnection();
                    if (connection == null) {
                        long time = System.currentTimeMillis();
                        InetAddress ipAddress = packet.getAddress();
                        String result = "TOO MANY CONNECTIONS\n";
                        Server.logQueryDNSBL(time, ipAddress, null, result);
                    } else {
                        connection.process(packet);
                    }
                } catch (SocketException ex) {
                    // Conexão fechada externamente pelo método close().
                }
            }
        } catch (Exception ex) {
            Server.logError(ex);
        } finally {
            Server.logDebug("Querie DNSBL server closed.");
        }
    }
    
    /**
     * Fecha todas as conexões e finaliza o servidor UDP.
     * @throws Exception se houver falha em algum fechamento.
     */
    @Override
    protected void close() throws Exception {
        while (CONNECTION_COUNT > 0) {
            CONNECION_SEMAPHORE.acquire();
            Connection connection = CONNECTION_POLL.poll();
            connection.close();
            CONNECTION_COUNT--;
        }
        Server.logDebug("Unbinding DSNBL socket on port " + PORT + "...");
        SERVER_SOCKET.close();
    }
}