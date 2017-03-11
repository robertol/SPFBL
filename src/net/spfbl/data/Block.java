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
package net.spfbl.data;

import net.spfbl.core.Reverse;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.Set;
import java.util.StringTokenizer;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.spfbl.core.Client;
import net.spfbl.core.Peer;
import net.spfbl.core.ProcessException;
import net.spfbl.core.Server;
import net.spfbl.core.User;
import net.spfbl.spf.SPF;
import net.spfbl.whois.Domain;
import net.spfbl.whois.Subnet;
import net.spfbl.whois.SubnetIPv4;
import net.spfbl.whois.SubnetIPv6;
import org.apache.commons.lang3.SerializationUtils;

/**
 * Representa a lista de bloqueio do sistema.
 * 
 * @author Leandro Carlos Rodrigues <leandro@spfbl.net>
 */
public class Block {
    
    /**
     * Flag que indica se o cache foi modificado.
     */
    private static boolean CHANGED = false;

    /**
     * Conjunto de remetentes bloqueados.
     */
    private static class SET {
        
        private static final HashSet<String> SET = new HashSet<String>();
        
        public static synchronized boolean isEmpty() {
            return SET.isEmpty();
        }
        
        public static synchronized void clear() {
            SET.clear();
        }
        
        public static TreeSet<String> get(User user) {
            if (user == null) {
                return get((String) null);
            } else {
                return get(user.getEmail());
            }
        }
        
        public static synchronized TreeSet<String> get(String user) {
            TreeSet<String> resultSet = new TreeSet<String>();
            for (String token : SET) {
                if (user == null && !token.contains(":")) {
                    resultSet.add(token);
                } else if (user != null && token.startsWith(user + ':')) {
                    int index = token.indexOf(':');
                    resultSet.add(token.substring(index+1));
                }
            }
            return resultSet;
        }
        
        public static synchronized int getAll(OutputStream outputStream) throws Exception {
            int count = 0;
            for (String token : SET) {
                outputStream.write(token.getBytes("UTF-8"));
                outputStream.write('\n');
                count++;
            }
            return count;
        }
                
        public static synchronized TreeSet<String> getAll() {
            TreeSet<String> set = new TreeSet<String>();
            set.addAll(SET);
            return set;
        }
        
        private static synchronized boolean addExact(String token) {
            return SET.add(token);
        }
        
        private static synchronized boolean dropExact(String token) {
            return SET.remove(token);
        }
        
        public static synchronized boolean contains(String token) {
            return SET.contains(token);
        }
    }
    
//    private static void logTrace(long time, String message) {
//        Server.log(time, Core.Level.TRACE, "BLOCK", message, (String) null);
//    }
    
    /**
     * Conjunto de critperios WHOIS para bloqueio.
     */
    private static class WHOIS {
        
        private static final HashMap<String,TreeSet<String>> MAP = new HashMap<String,TreeSet<String>>();
        
        public static synchronized boolean isEmpty() {
            return MAP.isEmpty();
        }
        
        public static synchronized void clear() {
            MAP.clear();
        }
        
        public static TreeSet<String> get(User user) {
            if (user == null) {
                return get((String) null);
            } else {
                return get(user.getEmail());
            }
        }
        
        public static synchronized TreeSet<String> get(String user) {
            TreeSet<String> resultSet = new TreeSet<String>();
            TreeSet<String> whoisSet = MAP.get(user);
            if (whoisSet != null) {
                for (String whois : whoisSet) {
                    resultSet.add("WHOIS/" + whois);
                }
            }
            return resultSet;
        }
        
        public static synchronized int getAll(OutputStream outputStream) throws Exception {
            int count = 0;
            for (String client : MAP.keySet()) {
                for (String whois : MAP.get(client)) {
                    if (client != null) {
                        outputStream.write(client.getBytes("UTF-8"));
                        outputStream.write(':');
                    }
                    outputStream.write("WHOIS/".getBytes("UTF-8"));
                    outputStream.write(whois.getBytes("UTF-8"));
                    outputStream.write('\n');
                    count++;
                }
            }
            return count;
        }
        
        public static synchronized TreeSet<String> getAll() {
            TreeSet<String> set = new TreeSet<String>();
            for (String client : MAP.keySet()) {
                for (String whois : MAP.get(client)) {
                    if (client == null) {
                        set.add("WHOIS/" + whois);
                    } else {
                        set.add(client + ":WHOIS/" + whois);
                    }
                }
            }
            return set;
        }
        
        private static synchronized boolean dropExact(String token) {
            int index = token.indexOf('/');
            String whois = token.substring(index+1);
            index = token.lastIndexOf(':', index);
            String client;
            if (index == -1) {
                client = null;
            } else {
                client = token.substring(0, index);
            }
            TreeSet<String> set = MAP.get(client);
            if (set == null) {
                return false;
            } else {
                boolean removed = set.remove(whois);
                if (set.isEmpty()) {
                    MAP.remove(client);
                }
                return removed;
            }
        }
        
        private static synchronized boolean addExact(String client, String token) {
            int index = token.indexOf('/');
            String whois = token.substring(index+1);
            TreeSet<String> set = MAP.get(client);
            if (set == null) {
                set = new TreeSet<String>();
                MAP.put(client, set);
            }
            return set.add(whois);
        }
        
        private static synchronized boolean addExact(String token) {
            int index = token.indexOf('/');
            String whois = token.substring(index+1);
            index = token.lastIndexOf(':', index);
            String client;
            if (index == -1) {
                client = null;
            } else {
                client = token.substring(0, index);
            }
            TreeSet<String> set = MAP.get(client);
            if (set == null) {
                set = new TreeSet<String>();
                MAP.put(client, set);
            }
            return set.add(whois);
        }
        
        private static synchronized TreeSet<String> getClientSet(String client) {
            return MAP.get(client);
        }
        
        public static boolean contains(String client, String host) {
            if (host == null) {
                return false;
            } else {
                TreeSet<String> whoisSet = getClientSet(client);
                if (whoisSet == null) {
                    return false;
                } else {
                    return whoisSet.contains(host);
                }
            }
        }
        
        private static String get(
                String client,
                Set<String> tokenSet,
                boolean autoBlock
        ) {
            if (tokenSet.isEmpty()) {
                return null;
            } else {
                TreeSet<String> subSet = new TreeSet<String>();
                TreeSet<String> whoisSet = getClientSet(null);
                if (whoisSet != null) {
                    subSet.addAll(whoisSet);
                }
                if (client != null) {
                    whoisSet = getClientSet(client);
                    if (whoisSet != null) {
                        for (String whois : whoisSet) {
                            subSet.add(client + ':' + whois);
                        }
                    }
                }
                if (subSet.isEmpty()) {
                    return null;
                } else {
//                    Server.logTrace("quering WHOIS");
                    for (String whois : subSet) {
                        try {
                            char signal = '=';
                            int indexValue = whois.indexOf(signal);
                            if (indexValue == -1) {
                                signal = '<';
                                indexValue = whois.indexOf(signal);
                                if (indexValue == -1) {
                                    signal = '>';
                                    indexValue = whois.indexOf(signal);
                                }
                            }
                            if (indexValue != -1) {
                                String userLocal = null;
                                int indexUser = whois.indexOf(':');
                                if (indexUser > 0 && indexUser < indexValue) {
                                    userLocal = whois.substring(0, indexUser);
                                }
                                String key = whois.substring(indexUser + 1, indexValue);
                                String criterion = whois.substring(indexValue + 1);
                                for (String token : tokenSet) {
                                    String value = null;
                                    if (Subnet.isValidIP(token)) {
                                        value = Subnet.getValue(token, key);
                                    } else if (token.startsWith(".") && Domain.isHostname(token)) {
                                        value = Domain.getValue(token, key);
                                    } else if (!token.startsWith(".") && Domain.isHostname(token.substring(1))) {
                                        value = Domain.getValue(token, key);
                                    }
                                    if (value != null) {
                                        if (signal == '=') {
                                            if (criterion.equals(value)) {
                                                if (autoBlock && (token = addDomain(userLocal, token)) != null) {
                                                    if (userLocal == null) {
                                                        Server.logDebug("new BLOCK '" + token + "' added by 'WHOIS/" + whois + "'.");
                                                        Peer.sendBlockToAll(token);
                                                    } else {
                                                        Server.logDebug("new BLOCK '" + userLocal + ":" + token + "' added by '" + userLocal + ":WHOIS/" + whois + "'.");
                                                    }
                                                }
                                                if (userLocal == null) {
                                                    return "WHOIS/" + whois;
                                                } else {
                                                    return userLocal + ":WHOIS/" + whois;
                                                }
                                            }
                                        } else if (value.length() > 0) {
                                            int criterionInt = parseIntWHOIS(criterion);
                                            int valueInt = parseIntWHOIS(value);
                                            if (signal == '<' && valueInt < criterionInt) {
                                                if (autoBlock && (token = addDomain(userLocal, token)) != null) {
                                                    if (userLocal == null) {
                                                        Server.logDebug("new BLOCK '" + token + "' added by 'WHOIS/" + whois + "'.");
                                                        Peer.sendBlockToAll(token);
                                                    } else {
                                                        Server.logDebug("new BLOCK '" + userLocal + ":" + token + "' added by '" + userLocal + ":WHOIS/" + whois + "'.");
                                                    }
                                                }
                                                if (userLocal == null) {
                                                    return "WHOIS/" + whois;
                                                } else {
                                                    return userLocal + ":WHOIS/" + whois;
                                                }
                                            } else if (signal == '>' && valueInt > criterionInt) {
                                                if (autoBlock && (token = addDomain(userLocal, token)) != null) {
                                                    if (userLocal == null) {
                                                        Server.logDebug("new BLOCK '" + token + "' added by 'WHOIS/" + whois + "'.");
                                                        Peer.sendBlockToAll(token);
                                                    } else {
                                                        Server.logDebug("new BLOCK '" + userLocal + ":" + token + "' added by '" + userLocal + ":WHOIS/" + whois + "'.");
                                                    }
                                                }
                                                if (userLocal == null) {
                                                    return "WHOIS/" + whois;
                                                } else {
                                                    return userLocal + ":WHOIS/" + whois;
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        } catch (Exception ex) {
                            Server.logError(ex);
                        }
                    }
                    return null;
                }
            }
        }
    }
    
    /**
     * Conjunto de DNSBL para bloqueio de IP.
     */
    private static class DNSBL {
        
        private static final HashMap<String,TreeSet<String>> MAP = new HashMap<String,TreeSet<String>>();
        
        public static synchronized boolean isEmpty() {
            return MAP.isEmpty();
        }
        
        public static synchronized void clear() {
            MAP.clear();
        }
        
        public static TreeSet<String> get(User user) {
            if (user == null) {
                return get((String) null);
            } else {
                return get(user.getEmail());
            }
        }
        
        public static synchronized TreeSet<String> get(String user) {
            TreeSet<String> resultSet = new TreeSet<String>();
            TreeSet<String> dnsblSet = MAP.get(user);
            if (dnsblSet != null) {
                for (String dnsbl : dnsblSet) {
                    resultSet.add("DNSBL=" + dnsbl);
                }
            }
            return resultSet;
        }
        
        public static synchronized int getAll(OutputStream outputStream) throws Exception {
            int count = 0;
            for (String client : MAP.keySet()) {
                for (String dnsbl : MAP.get(client)) {
                    if (client != null) {
                        outputStream.write(client.getBytes("UTF-8"));
                        outputStream.write(':');
                    }
                    outputStream.write("DNSBL=".getBytes("UTF-8"));
                    outputStream.write(dnsbl.getBytes("UTF-8"));
                    outputStream.write('\n');
                    count++;
                }
            }
            return count;
        }
        
        public static synchronized TreeSet<String> getAll() {
            TreeSet<String> set = new TreeSet<String>();
            for (String client : MAP.keySet()) {
                for (String dnsbl : MAP.get(client)) {
                    if (client == null) {
                        set.add("DNSBL=" + dnsbl);
                    } else {
                        set.add(client + ":DNSBL=" + dnsbl);
                    }
                }
            }
            return set;
        }
        
        private static synchronized boolean dropExact(String token) {
            int index = token.indexOf('=');
            String dnsbl = token.substring(index+1);
            index = token.lastIndexOf(':', index);
            String client;
            if (index == -1) {
                client = null;
            } else {
                client = token.substring(0, index);
            }
            TreeSet<String> set = MAP.get(client);
            if (set == null) {
                return false;
            } else {
                boolean removed = set.remove(dnsbl);
                if (set.isEmpty()) {
                    MAP.remove(client);
                }
                return removed;
            }
        }
        
        private static synchronized boolean addExact(String client, String token) {
            int index = token.indexOf('=');
            String dnsbl = token.substring(index+1);
            TreeSet<String> set = MAP.get(client);
            if (set == null) {
                set = new TreeSet<String>();
                MAP.put(client, set);
            }
            return set.add(dnsbl);
        }
        
        private static synchronized boolean addExact(String token) {
            int index = token.indexOf('=');
            String dnsbl = token.substring(index+1);
            index = token.lastIndexOf(':', index);
            String client;
            if (index == -1) {
                client = null;
            } else {
                client = token.substring(0, index);
            }
            TreeSet<String> set = MAP.get(client);
            if (set == null) {
                set = new TreeSet<String>();
                MAP.put(client, set);
            }
            return set.add(dnsbl);
        }
        
        public static synchronized boolean contains(String client, String dnsbl) {
            if (dnsbl == null) {
                return false;
            } else {
                TreeSet<String> dnsblSet = MAP.get(client);
                if (dnsblSet == null) {
                    return false;
                } else {
                    return dnsblSet.contains(dnsbl);
                }
            }
        }
        
        private static synchronized TreeSet<String> getClientSet(String client) {
            return MAP.get(client);
        }
        
        private static String get(String client, String ip) {
            if (ip == null) {
                return null;
            } else {
                TreeMap<String,TreeSet<String>> dnsblMap =
                        new TreeMap<String,TreeSet<String>>();
                TreeSet<String> registrySet = getClientSet(null);
                if (registrySet != null) {
                    for (String dnsbl : registrySet) {
                        int index = dnsbl.indexOf(';');
                        String server = dnsbl.substring(0, index);
                        String value = dnsbl.substring(index + 1);
                        TreeSet<String> dnsblSet = dnsblMap.get(server);
                        if (dnsblSet == null) {
                            dnsblSet = new TreeSet<String>();
                            dnsblMap.put(server, dnsblSet);
                        }
                        dnsblSet.add(value);
                    }
                }
                if (client != null) {
                    registrySet = getClientSet(client);
                    if (registrySet != null) {
                        for (String dnsbl : registrySet) {
                            int index = dnsbl.indexOf(';');
                            String server = dnsbl.substring(0, index);
                            String value = dnsbl.substring(index + 1);
                            TreeSet<String> dnsblSet = dnsblMap.get(server);
                            if (dnsblSet == null) {
                                dnsblSet = new TreeSet<String>();
                                dnsblMap.put(server, dnsblSet);
                            }
                            dnsblSet.add(value);
                        }
                    }
                }
                for (String server : dnsblMap.keySet()) {
                    TreeSet<String> valueSet = dnsblMap.get(server);
                    String listed = Reverse.getListed(ip, server, valueSet);
                    if (listed != null) {
                        Server.logDebug("IP " + ip + " is listed in '" + server + ";" + listed + "'.");
                        if (client == null) {
                            return "DNSBL=" + server + ";" + listed;
                        } else if ((registrySet = getClientSet(null)) == null) {
                            return client + ":DNSBL=" + server + ";" + listed;
                        } else if (registrySet.contains(server + ";" + listed)) {
                            return "DNSBL=" + server + ";" + listed;
                        } else {
                            return client + ":DNSBL=" + server + ";" + listed;
                        }
                    }
                }
                return null;
            }
        }
    }
    
    /**
     * Conjunto de REGEX para bloqueio.
     */
    private static class REGEX {
        
        private static final HashMap<String,ArrayList<Pattern>> MAP = new HashMap<String,ArrayList<Pattern>>();
        
        public static synchronized boolean isEmpty() {
            return MAP.isEmpty();
        }
        
        public static synchronized void clear() {
            MAP.clear();
        }
        
        public static synchronized void drop(String client) {
            MAP.remove(client);
        }
        
        public static TreeSet<String> get(User user) {
            if (user == null) {
                return get((String) null);
            } else {
                return get(user.getEmail());
            }
        }
        
        private static synchronized ArrayList<Pattern> getClientList(String client) {
            return MAP.get(client);
        }
        
        public static TreeSet<String> get(String user) {
            TreeSet<String> resultSet = new TreeSet<String>();
            ArrayList<Pattern> patternList = getClientList(user);
            if (patternList != null) {
                for (Pattern pattern : patternList) {
                    resultSet.add("REGEX=" + pattern.pattern());
                }
            }
            return resultSet;
        }
        
        private static synchronized ArrayList<String> getKeySet() {
            ArrayList<String> keySet = new ArrayList<String>();
            keySet.addAll(MAP.keySet());
            return keySet;
        }
        
        public static int getAll(OutputStream outputStream) throws Exception {
            int count = 0;
            for (String client : getKeySet()) {
                ArrayList<Pattern> patternList = getClientList(client);
                if (patternList != null) {
                    for (Pattern pattern : patternList) {
                        if (client != null) {
                            outputStream.write(client.getBytes("UTF-8"));
                            outputStream.write(':');
                        }
                        outputStream.write("REGEX=".getBytes("UTF-8"));
                        outputStream.write(pattern.toString().getBytes("UTF-8"));
                        outputStream.write('\n');
                        count++;
                    }
                }
            }
            return count;
        }
        
        public static TreeSet<String> getAll() {
            TreeSet<String> set = new TreeSet<String>();
            for (String client : getKeySet()) {
                ArrayList<Pattern> patternList = getClientList(client);
                if (patternList != null) {
                    for (Pattern pattern : patternList) {
                        if (client == null) {
                            set.add("REGEX=" + pattern);
                        } else {
                            set.add(client + ":REGEX=" + pattern);
                        }
                    }
                }
            }
            return set;
        }
        
        private static boolean dropExact(String token) {
            if (token == null) {
                return false;
            } else {
                int index = token.indexOf('=');
                String regex = token.substring(index+1);
                index = token.indexOf(':', index);
                String client;
                if (index == -1) {
                    client = null;
                } else if (Domain.isEmail(token.substring(0, index))) {
                    client = token.substring(0, index);
                } else {
                    client = null;
                }
                ArrayList<Pattern> list = getClientList(client);
                if (list == null) {
                    return false;
                } else {
                    for (index = 0; index < list.size(); index++) {
                        Pattern pattern = list.get(index);
                        if (regex.equals(pattern.pattern())) {
                            list.remove(index);
                            if (list.isEmpty()) {
                                drop(client);
                            }
                            return true;
                        }
                    }
                    return false;
                }
            }
        }
        
        private static synchronized boolean addExact(String client, String token) {
            int index = token.indexOf('=');
            String regex = token.substring(index+1);
            ArrayList<Pattern> list = MAP.get(client);
            if (list == null) {
                list = new ArrayList<Pattern>();
                MAP.put(client, list);
            }
            Pattern pattern = Pattern.compile(regex);
            return list.add(pattern);
        }
        
        private static synchronized boolean addExact(String token) {
            int index = token.indexOf('=');
            String regex = token.substring(index+1);
            index = token.lastIndexOf(':', index);
            String client;
            if (index == -1) {
                client = null;
            } else {
                client = token.substring(0, index);
            }
            ArrayList<Pattern> list = MAP.get(client);
            if (list == null) {
                list = new ArrayList<Pattern>();
                MAP.put(client, list);
            }
            for (index = 0; index < list.size(); index++) {
                Pattern pattern = list.get(index);
                if (regex.equals(pattern.pattern())) {
                    return false;
                }
            }
            Pattern pattern = Pattern.compile(regex);
            list.add(pattern);
            return true;
        }
        
        public static boolean contains(String client, String regex) {
            if (regex == null) {
                return false;
            } else {
                ArrayList<Pattern> patternList = getClientList(client);
                if (patternList == null) {
                    return false;
                } else {
                    for (Pattern pattern : patternList) {
                        if (regex.equals(pattern.pattern())) {
                            return true;
                        }
                    }
                }
                return false;
            }
        }
        
        public static String find(String token) {
            if (token == null) {
                return null;
            } else {
                ArrayList<Pattern> patternList = getClientList(null);
                if (patternList == null) {
                    return null;
                } else {
                    for (Pattern pattern : patternList) {
                        Matcher matcher = pattern.matcher(token);
                        if (matcher.matches()) {
                            return "REGEX=" + pattern.pattern();
                        }
                    }
                }
                return null;
            }
        }
        
        private static String get(
                String client,
                Collection<String> tokenList,
                boolean autoBlock
                ) throws ProcessException {
            if (tokenList.isEmpty()) {
                return null;
            } else {
                String result = null;
                ArrayList<Pattern> patternList = getClientList(null);
                if (patternList != null) {
                    for (Object object : patternList.toArray()) {
                        Pattern pattern = (Pattern) object;
                        for (String token : tokenList) {
                            if (token.contains("@") == pattern.pattern().contains("@")) {
                                Matcher matcher = pattern.matcher(token);
                                if (matcher.matches()) {
                                    String regex = "REGEX=" + pattern.pattern();
                                    if (autoBlock && Block.addExact(token)) {
                                        Server.logDebug("new BLOCK '" + token + "' added by '" + regex + "'.");
                                        if (client == null) {
                                            Peer.sendBlockToAll(token);
                                        }
                                    }
                                    result = regex;
                                    break;
                                }
                            }
                        }
                    }
                }
                if (result == null && client != null) {
                    patternList = getClientList(client);
                    if (patternList != null) {
                        for (Object object : patternList.toArray()) {
                            Pattern pattern = (Pattern) object;
                            for (String token : tokenList) {
                                if (token.contains("@") == pattern.pattern().contains("@")) {
                                    Matcher matcher = pattern.matcher(token);
                                    if (matcher.matches()) {
                                        String regex = "REGEX=" + pattern.pattern();
                                        token = client + ":" + token;
                                        if (autoBlock && addExact(token)) {
                                            Server.logDebug("new BLOCK '" + token + "' added by '" + client + ":" + regex + "'.");
                                        }
                                        result = client + ":" + regex;
                                        break;
                                    }
                                }
                            }
                        }
                    }
                }
                return result;
            }
        }
    }
    
    /**
     * Representa o conjunto de blocos IP bloqueados.
     */
    private static class CIDR {
        
        private static final HashMap<String,TreeSet<String>> MAP = new HashMap<String,TreeSet<String>>();
        
        public static synchronized boolean isEmpty() {
            return MAP.isEmpty();
        }
        
        public static synchronized void clear() {
            MAP.clear();
        }
        
        public static synchronized ArrayList<String> getKeySet() {
            ArrayList<String> resultSet = new ArrayList<String>();
            resultSet.addAll(MAP.keySet());
            return resultSet;
        }
        
        public static synchronized TreeSet<String> getClientSet(String client) {
            return MAP.get(client);
        }
        
        public static synchronized Object[] getClientArray(String client) {
            TreeSet<String> clientSet = MAP.get(client);
            if (clientSet == null) {
                return null;
            } else {
                return clientSet.toArray();
            }
        }
        
        public static synchronized TreeSet<String> getExtended() {
            TreeSet<String> returnSet = new TreeSet<String>();
            TreeSet<String> cidrSet = MAP.get(null);
            if (cidrSet != null) {
                returnSet.addAll(cidrSet);
            }
            return returnSet;
        }
        
        public static TreeSet<String> get(User user) {
            if (user == null) {
                return get((String) null);
            } else {
                return get(user.getEmail());
            }
        }
        
        public static synchronized TreeSet<String> get(String user) {
            TreeSet<String> resultSet = new TreeSet<String>();
            TreeSet<String> cidrSet = MAP.get(user);
            if (cidrSet != null) {
                for (String cidr : cidrSet) {
                    if (cidr.contains(":")) {
                        cidr = SubnetIPv6.normalizeCIDRv6(cidr);
                    } else {
                        cidr = SubnetIPv4.normalizeCIDRv4(cidr);
                    }
                    resultSet.add("CIDR=" + cidr);
                }
            }
            return resultSet;
        }
        
        public static int getAll(OutputStream outputStream) throws Exception {
            int count = 0;
            for (String client : getKeySet()) {
                TreeSet<String> clientSet = getClientSet(client);
                if (clientSet != null) {
                    for (String cidr : clientSet) {
                        if (cidr.contains(":")) {
                            cidr = SubnetIPv6.normalizeCIDRv6(cidr);
                        } else {
                            cidr = SubnetIPv4.normalizeCIDRv4(cidr);
                        }
                        if (client != null) {
                            outputStream.write(client.getBytes("UTF-8"));
                            outputStream.write(':');
                        }
                        outputStream.write("CIDR=".getBytes("UTF-8"));
                        outputStream.write(cidr.getBytes("UTF-8"));
                        outputStream.write('\n');
                        count++;
                    }
                }
            }
            return count;
        }
        
        public static TreeSet<String> getAll() {
            TreeSet<String> set = new TreeSet<String>();
            for (String client : getKeySet()) {
                Object[] clientArray = getClientArray(client);
                if (clientArray != null) {
                    for (Object element : clientArray) {
                        String cidr = (String) element;
                        if (cidr.contains(":")) {
                            cidr = SubnetIPv6.normalizeCIDRv6(cidr);
                        } else {
                            cidr = SubnetIPv4.normalizeCIDRv4(cidr);
                        }
                        if (client == null) {
                            set.add("CIDR=" + cidr);
                        } else {
                            set.add(client + ":CIDR=" + cidr);
                        }
                    }
                }
            }
            return set;
        }
        
        private static boolean split(String cidr) {
            if (CIDR.dropExact(cidr)) {
                cidr = cidr.substring(5);
                byte mask = Subnet.getMask(cidr);
                String first = Subnet.getFirstIP(cidr);
                String last = Subnet.getLastIP(cidr);
                int max = SubnetIPv4.isValidIPv4(first) ? 32 : 64;
                if (mask < max) {
                    mask++;
                    String cidr1 = first + "/" + mask;
                    String cidr2 = last + "/" + mask;
                    cidr1 = "CIDR=" + Subnet.normalizeCIDR(cidr1);
                    cidr2 = "CIDR=" + Subnet.normalizeCIDR(cidr2);
                    boolean splited = true;
                    try {
                        if (!CIDR.addExact(cidr1, false)) {
                            splited = false;
                        }
                    } catch (ProcessException ex) {
                        splited = false;
                    }
                    try {
                        if (!CIDR.addExact(cidr2, false)) {
                            splited = false;
                        }
                    } catch (ProcessException ex) {
                        splited = false;
                    }
                    return splited;
                } else {
                    return false;
                }
            } else {
                return false;
            }
        }
        
        private static synchronized boolean dropExact(String token) {
            if (token == null) {
                return false;
            } else {
                int index = token.indexOf('=');
                String cidr = token.substring(index+1);
                index = token.lastIndexOf(':', index);
                String client;
                if (index == -1) {
                    client = null;
                } else {
                    client = token.substring(0, index);
                }
                TreeSet<String> set = MAP.get(client);
                if (set == null) {
                    return false;
                } else {
                    String key = Subnet.expandCIDR(cidr);
                    boolean removed = set.remove(key);
                    if (set.isEmpty()) {
                        MAP.remove(client);
                    }
                    return removed;
                }
            }
        }
        
        public static void simplify() {
            try {
                TreeSet<String> cidrSet = getClientSet(null);
                if (cidrSet != null && !cidrSet.isEmpty()) {
                    String cidrExtended = cidrSet.first();
                    do {
                        try {
                            if (cidrExtended.contains(".")) {
                                String cidrSmaller = SubnetIPv4.normalizeCIDRv4(cidrExtended);
                                int mask = Subnet.getMask(cidrSmaller);
                                if (mask > 16) {
                                    String ipFirst = SubnetIPv4.getFirstIPv4(cidrSmaller);
                                    String cidrBigger = SubnetIPv4.normalizeCIDRv4(ipFirst + "/" + (mask - 1));
                                    ipFirst = SubnetIPv4.getFirstIPv4(cidrBigger);
                                    String ipLast = SubnetIPv4.getLastIPv4(cidrBigger);
                                    String cidr1 = SubnetIPv4.normalizeCIDRv4(ipFirst + "/" + mask);
                                    if (CIDR.contains((String) null, cidr1)) {
                                        String cidr2 = SubnetIPv4.normalizeCIDRv4(ipLast + "/" + mask);
                                        if (CIDR.contains((String) null, cidr2)) {
                                            CIDR.addExact(cidrBigger, true);
                                            Server.logTrace("CIDR " + cidr1 + " and " + cidr2 + " simplified to " + cidrBigger + ".");
                                        }
                                    }
                                }
                            }
                        } catch (ProcessException ex) {
                            Server.logError(ex);
                        }
                    } while ((cidrExtended = cidrSet.higher(cidrExtended)) != null);
                }
            } catch (Exception ex) {
                Server.logError(ex);
            }
        }
        
        private static synchronized boolean addExact(
                String client, String token
        ) {
            int index = token.indexOf('=');
            String cidr = token.substring(index+1);
            TreeSet<String> set = MAP.get(client);
            if (set == null) {
                set = new TreeSet<String>();
                MAP.put(client, set);
            }
            String key = Subnet.expandCIDR(cidr);
            return set.add(key);
        }
                
        private static synchronized boolean addExact(
                String token, boolean overlap
        ) throws ProcessException {
            int index = token.indexOf('=');
            String cidr = token.substring(index+1);            
            index = token.lastIndexOf(':', index);
            String client;
            if (index == -1) {
                client = null;
            } else {
                client = token.substring(0, index);
            }
            TreeSet<String> set = MAP.get(client);
            if (set == null) {
                set = new TreeSet<String>();
                MAP.put(client, set);
            }
            String key = Subnet.expandCIDR(cidr);
            if (set.contains(key)) {
                return false;
            } else {
                String firstCIDR = Subnet.getFirstIP(cidr);
                String lastCIDR = Subnet.getLastIP(cidr);
                String firstExpanded = Subnet.expandIP(firstCIDR) + "/00";
                String lastExpanded = Subnet.expandIP(lastCIDR) + "/99";
                String floorExpanded = set.floor(firstExpanded);
                String floor = Subnet.normalizeCIDR(floorExpanded);
                TreeSet<String> intersectsSet = new TreeSet<String>();
                intersectsSet.addAll(set.subSet(firstExpanded, lastExpanded));
                if (Subnet.containsIP(floor, firstCIDR)) {
                    intersectsSet.add(floorExpanded);
                }
                TreeSet<String> overlapSet = new TreeSet<String>();
                StringBuilder errorBuilder = new StringBuilder();
                for (String elementExpanded : intersectsSet) {
                    String element = Subnet.normalizeCIDR(elementExpanded);
                    String elementFirst = Subnet.getFirstIP(element);
                    String elementLast = Subnet.getLastIP(element);
                    if (!Subnet.containsIP(cidr, elementFirst)) {
                        errorBuilder.append("INTERSECTS ");
                        errorBuilder.append(element);
                        errorBuilder.append('\n');
                    } else if (!Subnet.containsIP(cidr, elementLast)) {
                        errorBuilder.append("INTERSECTS ");
                        errorBuilder.append(element);
                        errorBuilder.append('\n');
                    } else if (overlap) {
                        overlapSet.add(elementExpanded);
                    } else {
                        errorBuilder.append("CONTAINS ");
                        errorBuilder.append(element);
                        errorBuilder.append('\n');
                    }
                }
                String error = errorBuilder.toString();
                if (error.length() == 0) {
                    set.removeAll(overlapSet);
                    return set.add(key);
                } else {
                    throw new ProcessException(error);
                }
            }
        }
        
        public static boolean contains(Client client, String cidr) {
            if (client == null) {
                return contains((String) null, cidr);
            } else {
                return contains(client.getEmail(), cidr);
            }
        }
        
        public static boolean contains(String client, String cidr) {
            if (cidr == null) {
                return false;
            } else {
                String key = Subnet.expandCIDR(cidr);
                TreeSet<String> cidrSet = getClientSet(client);
                if (cidrSet == null) {
                    return false;
                } else {
                    return cidrSet.contains(key);
                }
            }
        }
        
        private static String getFloor(String client, String ip) {
            TreeSet<String> cidrSet = getClientSet(client);
            if (cidrSet == null || cidrSet.isEmpty()) {
                return null;
            } else if (SubnetIPv4.isValidIPv4(ip)) {
                String key = SubnetIPv4.expandIPv4(ip);
                String cidr = cidrSet.floor(key + "/9");
                if (cidr == null) {
                    return null;
                } else if (cidr.contains(".")) {
                    return SubnetIPv4.normalizeCIDRv4(cidr);
                } else {
                    return null;
                }
            } else if (SubnetIPv6.isValidIPv6(ip)) {
                String key = SubnetIPv6.expandIPv6(ip);
                String cidr = cidrSet.floor(key + "/9");
                if (cidr == null) {
                    return null;
                } else if (cidr.contains(":")) {
                    return SubnetIPv6.normalizeCIDRv6(cidr);
                } else {
                    return null;
                }
            } else {
                return null;
            }
        }

        public static String get(String client, String ip) {
//            long time = System.currentTimeMillis();
            String result;
            String cidr = getFloor(null, ip);
            if (Subnet.containsIP(cidr, ip)) {
                result = "CIDR=" + cidr;
            } else if (client == null) {
                result = null;
            } else if ((cidr = getFloor(client, ip)) == null) {
                result = null;
            } else if (Subnet.containsIP(cidr, ip)) {
                result = client + ":CIDR=" + cidr;
            } else {
                result = null;
            }
//            logTrace(time, "CIDR lookup for '" + ip + "'.");
            return result;
        }
    }

    public static boolean dropExact(String token) {
        if (token == null) {
            return false;
        } else if (token.contains("DNSBL=")) {
            if (DNSBL.dropExact(token)) {
                return CHANGED = true;
            } else {
                return false;
            }
        } else if (token.contains("CIDR=")) {
            if (CIDR.dropExact(token)) {
                return CHANGED = true;
            } else {
                return false;
            }
        } else if (token.contains("REGEX=")) {
            if (REGEX.dropExact(token)) {
                return CHANGED = true;
            } else {
                return false;
            }
        } else if (token.contains("WHOIS/")) {
            if (WHOIS.dropExact(token)) {
                return CHANGED = true;
            } else {
                return false;
            }
        } else if (SET.dropExact(token)) {
            return CHANGED = true;
        } else {
            return false;
        }
    }

    public static boolean dropAll() {
        SET.clear();
        CIDR.clear();
        REGEX.clear();
        DNSBL.clear();
        WHOIS.clear();
        CHANGED = true;
        return true;
    }

    public static boolean addExact(String token) throws ProcessException {
        if (token == null) {
            return false;
        } else if (token.contains("WHOIS/")) {
            if (WHOIS.addExact(token)) {
                Peer.releaseAll(token);
                CHANGED = true;
                return true;
            } else {
                return false;
            }
        } else if (token.contains("DNSBL=")) {
            if (DNSBL.addExact(token)) {
                Peer.releaseAll(token);
                CHANGED = true;
                return true;
            } else {
                return false;
            }
        } else if (token.contains("CIDR=")) {
            if (CIDR.addExact(token, false)) {
                Peer.releaseAll(token);
                CHANGED = true;
                return true;
            } else {
                return false;
            }
        } else if (token.contains("REGEX=")) {
            if (REGEX.addExact(token)) {
                Peer.releaseAll(token);
                CHANGED = true;
                return true;
            } else {
                return false;
            }
        } else if (SET.addExact(token)) {
            Peer.releaseAll(token);
            CHANGED = true;
            return true;
        } else {
            return false;
        }
    }
    
    public static TreeSet<String> getExtendedCIDR() {
        return CIDR.getExtended();
    }

    public static TreeSet<String> getAll() throws ProcessException {
        TreeSet<String> blockSet = SET.getAll();
        blockSet.addAll(CIDR.getAll());
        blockSet.addAll(REGEX.getAll());
        blockSet.addAll(DNSBL.getAll());
        blockSet.addAll(WHOIS.getAll());
        return blockSet;
    }
    
    public static TreeSet<String> get(String user) throws ProcessException {
        TreeSet<String> blockSet = SET.get(user);
        blockSet.addAll(CIDR.get(user));
        blockSet.addAll(REGEX.get(user));
        blockSet.addAll(DNSBL.get(user));
        blockSet.addAll(WHOIS.get(user));
        return blockSet;
    }
    
    public static boolean containsExact(User user, String token) {
        if (user == null || token == null) {
            return false;
        } else {
            return SET.contains(user.getEmail() + ":" + token);
        }
    }

    public static boolean containsExact(String token) {
        if (token.contains("WHOIS/")) {
            int index = token.indexOf('/');
            String whois = token.substring(index+1);
            index = token.lastIndexOf(':', index);
            String client;
            if (index == -1) {
                client = null;
            } else {
                client = token.substring(0, index);
            }
            return WHOIS.contains(client, whois);
        } else if (token.contains("DNSBL=")) {
            int index = token.indexOf('=');
            String dnsbl = token.substring(index+1);
            index = token.lastIndexOf(':', index);
            String client;
            if (index == -1) {
                client = null;
            } else {
                client = token.substring(0, index);
            }
            return DNSBL.contains(client, dnsbl);
        } else if (token.contains("CIDR=")) {
            int index = token.indexOf('=');
            String cidr = token.substring(index+1);
            index = token.lastIndexOf(':', index);
            String client;
            if (index == -1) {
                client = null;
            } else {
                client = token.substring(0, index);
            }
            return CIDR.contains(client, cidr);
        } else if (token.contains("REGEX=")) {
            int index = token.indexOf('=');
            String regex = token.substring(index+1);
            index = token.lastIndexOf(':', index);
            String client;
            if (index == -1) {
                client = null;
            } else {
                client = token.substring(0, index);
            }
            return REGEX.contains(client, regex);
        } else {
            return SET.contains(token);
        }
    }

    private static String addDomain(String user, String token) {
        try {
            if (token == null) {
                return null;
            } else if (token.startsWith("@") && (token = Domain.extractDomain(token.substring(1), true)) != null) {
                if (user == null && addExact(token)) {
                    return token;
                } else if (user != null && addExact(user + ':' + token)) {
                    return user + ':' + token;
                } else {
                    return null;
                }
            } else if (token.startsWith(".") && (token = Domain.extractDomain(token, true)) != null) {
                if (user == null && addExact(token)) {
                    return token;
                } else if (user != null && addExact(user + ':' + token)) {
                    return user + ':' + token;
                } else {
                    return null;
                }
            } else {
                return null;
            }
        } catch (ProcessException ex) {
            return null;
        }
    }
    
    private static String normalizeTokenBlock(String token) throws ProcessException {
        int index = token.indexOf(':');
        if (index > 0 && Domain.isEmail(token.substring(0, index))) {
            String client = token.substring(0, index).toLowerCase();
            token = token.substring(index + 1);
            token = SPF.normalizeToken(token, true, true, true, true,
//                    false,
                    true);
            if (token == null) {
                return null;
            } else {
                return client + ":" + token;
            }
        } else {
            return SPF.normalizeToken(token, true, true, true, true,
//                    false,
                    true);
        }
    }
    
    public static boolean tryAdd(String token) {
        try {
            return add(token) != null;
        } catch (ProcessException ex) {
            return false;
        }
    }

    public static String add(String token) throws ProcessException {
        if ((token = normalizeTokenBlock(token)) == null) {
            throw new ProcessException("TOKEN INVALID");
        } else if (addExact(token)) {
            return token;
        } else {
            return null;
        }
    }
    
    public static boolean overlap(String cidr) throws ProcessException {
        if ((cidr = normalizeTokenBlock(cidr)) == null) {
            throw new ProcessException("TOKEN INVALID");
        } else if (!cidr.startsWith("CIDR=")) {
            throw new ProcessException("TOKEN INVALID");
        } else {
            return CIDR.addExact(cidr, true);
        }
    }
    
    public static boolean add(String client, String token) throws ProcessException {
        if (client == null || !Domain.isEmail(client)) {
            throw new ProcessException("CLIENT INVALID");
        } else if ((token = normalizeTokenBlock(token)) == null) {
            throw new ProcessException("TOKEN INVALID");
        } else {
            return addExact(client + ':' + token);
        }
    }
    
    public static boolean add(User user, String token) throws ProcessException {
        if (user == null) {
            throw new ProcessException("USER INVALID");
        } else if ((token = normalizeTokenBlock(token)) == null) {
            throw new ProcessException("TOKEN INVALID");
        } else {
            return addExact(user.getEmail() + ":" + token);
        }
    }
    
    public static String addIfNotNull(User user, String token) throws ProcessException {
        if (user == null) {
            return null;
        } else if ((token = normalizeTokenBlock(token)) == null) {
            return null;
        } else if (addExact(user.getEmail() + ":" + token)) {
            return user.getEmail() + ":" + token;
        } else {
            return null;
        }
    }

    public static boolean add(Client client, String token) throws ProcessException {
        if (client == null || !client.hasEmail()) {
            throw new ProcessException("CLIENT INVALID");
        } else if ((token = normalizeTokenBlock(token)) == null) {
            throw new ProcessException("TOKEN INVALID");
        } else {
            return addExact(client.getEmail() + ':' + token);
        }
    }

    public static boolean drop(String token) throws ProcessException {
        if ((token = normalizeTokenBlock(token)) == null) {
            throw new ProcessException("TOKEN INVALID");
        } else if (dropExact(token)) {
            return true;
        } else {
            return false;
        }
    }
    
    public static boolean drop(String client, String token) throws ProcessException {
        if (client == null || !Domain.isEmail(client)) {
            throw new ProcessException("CLIENT INVALID");
        } else if ((token = normalizeTokenBlock(token)) == null) {
            throw new ProcessException("TOKEN INVALID");
        } else {
            return dropExact(client + ':' + token);
        }
    }
    
    public static boolean drop(User user, String token) throws ProcessException {
        if (user == null) {
            throw new ProcessException("USER INVALID");
        } else if ((token = normalizeTokenBlock(token)) == null) {
            throw new ProcessException("TOKEN INVALID");
        } else {
            return dropExact(user.getEmail() + ':' + token);
        }
    }

    public static boolean drop(Client client, String token) throws ProcessException {
        if (client == null || !client.hasEmail()) {
            throw new ProcessException("CLIENT INVALID");
        } else if ((token = normalizeTokenBlock(token)) == null) {
            throw new ProcessException("TOKEN INVALID");
        } else {
            return dropExact(client.getEmail() + ':' + token);
        }
    }

    public static TreeSet<String> get(Client client, User user) throws ProcessException {
        TreeSet<String> blockSet = new TreeSet<String>();
        // Definição do e-mail do usuário.
        String userEmail = null;
        if (user != null) {
            userEmail = user.getEmail();
        } else if (client != null) {
            userEmail = client.getEmail();
        }
        if (userEmail != null) {
            for (String token : get(userEmail)) {
                int index = token.indexOf(':') + 1;
                token = token.substring(index);
                blockSet.add(token);
            }
        }
        return blockSet;
    }
    
    public static TreeSet<String> getSet(User user) throws ProcessException {
        return SET.get(user);
    }
    
    public static TreeSet<String> getCIDRSet(User user) throws ProcessException {
        return CIDR.get(user);
    }
    
    public static TreeSet<String> getREGEXSet(User user) throws ProcessException {
        return REGEX.get(user);
    }
    
    public static TreeSet<String> getWHOISSet(User user) throws ProcessException {
        return WHOIS.get(user);
    }
    
    public static TreeSet<String> getDNSBLSet(User user) throws ProcessException {
        return DNSBL.get(user);
    }

    public static TreeSet<String> getAll(Client client, User user) throws ProcessException {
        TreeSet<String> blockSet = new TreeSet<String>();
        // Definição do e-mail do usuário.
        String userEmail = null;
        if (user != null) {
            userEmail = user.getEmail();
        } else if (client != null) {
            userEmail = client.getEmail();
        }
        for (String token : getAll()) {
            if (!token.contains(":")) {
                blockSet.add(token);
            } else if (userEmail != null && token.startsWith(userEmail + ':')) {
                int index = token.indexOf(':') + 1;
                token = token.substring(index);
                blockSet.add(token);
            }
        }
        return blockSet;
    }

    public static TreeSet<String> getAllTokens(String value) {
        TreeSet<String> blockSet = new TreeSet<String>();
        if (Subnet.isValidIP(value)) {
            String ip = Subnet.normalizeIP(value);
            if (SET.contains(ip)) {
                blockSet.add(ip);
            }
        } else if (Subnet.isValidCIDR(value)) {
            String cidr = Subnet.normalizeCIDR(value);
            if (CIDR.contains((String) null, cidr)) {
                blockSet.add(cidr);
            }
            TreeSet<String> set = SET.getAll();
            for (String ip : set) {
                if (Subnet.containsIP(cidr, ip)) {
                    blockSet.add(ip);
                }
            }
            for (String ip : set) {
                if (SubnetIPv6.containsIP(cidr, ip)) {
                    blockSet.add(ip);
                }
            }
        } else if (Domain.isHostname(value)) {
            LinkedList<String> regexList = new LinkedList<String>();
            String host = Domain.normalizeHostname(value, true);
            do {
                int index = host.indexOf('.') + 1;
                host = host.substring(index);
                if (Block.dropExact('.' + host)) {
                    blockSet.add('.' + host);
                    regexList.addFirst('.' + host);
                }
            } while (host.contains("."));
        } else if (SET.contains(value)) {
            blockSet.add(value);
        }
        return blockSet;
    }
    
    public static int getAll(OutputStream outputStream) throws Exception {
        int count = SET.getAll(outputStream);
        count += CIDR.getAll(outputStream);
        count += REGEX.getAll(outputStream);
        count += DNSBL.getAll(outputStream);
        count += WHOIS.getAll(outputStream);
        outputStream.flush();
        return count;
    }
    
    public static int get(OutputStream outputStream) throws IOException {
        int count = 0;
        TreeSet<String> set;
        if ((set = SET.get((User) null)) != null) {
            for (String token : set) {
                outputStream.write(token.getBytes("UTF-8"));
                outputStream.write('\n');
                outputStream.flush();
                count++;
            }
        }
        if ((set = CIDR.get((User) null)) != null) {
            for (String token : set) {
                outputStream.write(token.getBytes("UTF-8"));
                outputStream.write('\n');
                outputStream.flush();
                count++;
            }
        }
        if ((set = REGEX.get((User) null)) != null) {
            for (String token : set) {
                outputStream.write(token.getBytes("UTF-8"));
                outputStream.write('\n');
                outputStream.flush();
                count++;
            }
        }
        if ((set = DNSBL.get((User) null)) != null) {
            for (String token : set) {
                outputStream.write(token.getBytes("UTF-8"));
                outputStream.write('\n');
                outputStream.flush();
                count++;
            }
        }
        if ((set = WHOIS.get((User) null)) != null) {
            for (String token : set) {
                outputStream.write(token.getBytes("UTF-8"));
                outputStream.write('\n');
                outputStream.flush();
                count++;
            }
        }
        return count;
    }

    public static TreeSet<String> get() throws ProcessException {
        TreeSet<String> blockSet = new TreeSet<String>();
        for (String token : getAll()) {
            if (!token.contains(":")) {
                blockSet.add(token);
            }
        }
        return blockSet;
    }
    
    public static void clear(String token, String name) {
        try {
            TreeSet<String> blockSet = new TreeSet<String>();
            String block;
            while ((block = Block.find(null, null, token, false)) != null) {
                if (blockSet.contains(block)) {
                    throw new ProcessException("FATAL BLOCK ERROR");
                } else if (Block.drop(block)) {
                    Server.logInfo("false positive BLOCK '" + block + "' detected by '" + name + "'.");
                }
                blockSet.add(block);
            }
        } catch (ProcessException ex) {
            Server.logError(ex);
        }
    }
    
    public static void clear(User user, String token, String name) {
        try {
            TreeSet<String> blockSet = new TreeSet<String>();
            String block;
            while ((block = Block.find(null, user, token, false)) != null) {
                if (blockSet.contains(block)) {
                    throw new ProcessException("FATAL BLOCK ERROR");
                } else if (Block.drop(block)) {
                    Server.logInfo("false positive BLOCK '" + block + "' detected by '" + name + "'.");
                }
                blockSet.add(block);
            }
        } catch (ProcessException ex) {
            Server.logError(ex);
        }
    }
    
    public static String clearCIDR(String ip, int mask) {
        if (SubnetIPv4.isValidIPv4(ip)) {
            String cidr;
            while ((cidr = CIDR.get(null, ip)) != null && Subnet.getMask(cidr) < mask) {
                CIDR.split(cidr);
            }
            if (CIDR.dropExact(cidr)) {
                return cidr;
            } else {
                return null;
            }
        } else {
            return null;
        }
    }
    
    public static boolean clearCIDR(String ip, String admin) {
        if (ip == null) {
            return false;
        } else {
            String cidr;
            int mask = SubnetIPv4.isValidIPv4(ip) ? 32 : 64;
            if ((cidr = Block.clearCIDR(ip, mask)) != null) {
                Server.logInfo("false positive BLOCK '" + cidr + "' detected by '" + admin + "'.");
                return true;
            } else {
                return false;
            }
        }
    }
    
    public static String find(
            User user,
            String token
            )  {
        // Definição do e-mail do usuário.
        String userEmail = null;
        if (user != null) {
            userEmail = user.getEmail();
        }
        return find(userEmail, token, false);
    }
    
    public static String find(
            User user,
            String token,
            boolean autoBlock
            ) {
        // Definição do e-mail do usuário.
        String userEmail = null;
        if (user != null) {
            userEmail = user.getEmail();
        }
        return find(userEmail, token, autoBlock);
    }
    
    public static String find(
            Client client,
            User user,
            String token,
            boolean autoBlock
            ) {
        // Definição do e-mail do usuário.
        String userEmail = null;
        if (user != null) {
            userEmail = user.getEmail();
        } else if (client != null) {
            userEmail = client.getEmail();
        }
        return find(userEmail, token, autoBlock);
    }
    
    public static String find(
            String userEmail,
            String token,
            boolean autoBlock
            ) {
        TreeSet<String> whoisSet = new TreeSet<String>();
        LinkedList<String> regexList = new LinkedList<String>();
        if (token == null) {
            return null;
        } else if (Domain.isEmail(token)) {
            String sender = token.toLowerCase();
            int index1 = sender.indexOf('@');
            int index2 = sender.lastIndexOf('@');
            String part = sender.substring(0, index1 + 1);
            String senderDomain = sender.substring(index2);
            if (SET.contains(sender)) {
                return sender;
            } else if (userEmail != null && SET.contains(userEmail + ':' + sender)) {
                return userEmail + ':' + sender;
            } else if (SET.contains(part)) {
                return part;
            } else if (userEmail != null && SET.contains(userEmail + ':' + part)) {
                return userEmail + ':' + part;
            } else if (SET.contains(senderDomain)) {
                return senderDomain;
            } else if (userEmail != null && SET.contains(userEmail + ':' + senderDomain)) {
                return userEmail + ':' + senderDomain;
            } else {
                int index3 = senderDomain.length();
                while ((index3 = senderDomain.lastIndexOf('.', index3 - 1)) > index2) {
                    String subdomain = senderDomain.substring(0, index3 + 1);
                    if (SET.contains(subdomain)) {
                        return subdomain;
                    } else if (userEmail != null && SET.contains(userEmail + ':' + subdomain)) {
                        return userEmail + ':' + subdomain;
                    }
                }
                String host = '.' + senderDomain.substring(1);
                do {
                    int index = host.indexOf('.') + 1;
                    host = host.substring(index);
                    String token2 = '.' + host;
                    if (SET.contains(token2)) {
                        return token2;
                    } else if (userEmail != null && SET.contains(userEmail + ':' + token2)) {
                        return userEmail + ':' + token2;
                    }
                    regexList.addFirst(token2);
                } while (host.contains("."));
                int index4 = sender.length();
                while ((index4 = sender.lastIndexOf('.', index4 - 1)) > index2) {
                    String subsender = sender.substring(0, index4 + 1);
                    if (SET.contains(subsender)) {
                        return subsender;
                    } else if (userEmail != null && SET.contains(userEmail + ':' + subsender)) {
                        return userEmail + ':' + subsender;
                    }
                }
            }
            if (senderDomain.endsWith(".br")) {
                whoisSet.add(senderDomain);
            }
            regexList.add(sender);
        } else if (Subnet.isValidIP(token)) {
            token = Subnet.normalizeIP(token);
            String cidr;
            String dnsbl;
            if (SET.contains(token)) {
                return token;
            } else if (userEmail != null && SET.contains(userEmail + ':' + token)) {
                return userEmail + ':' + token;
            } else if ((cidr = CIDR.get(userEmail, token)) != null) {
                return cidr;
            } else if ((dnsbl = DNSBL.get(userEmail, token)) != null) {
                return dnsbl;
            }
            Reverse reverse = Reverse.get(token);
            if (reverse != null) {
                for (String host : reverse.getAddressSet()) {
                    String block = find(userEmail, host, autoBlock);
                    if (block != null) {
                        return block;
                    }
                }
            }
            regexList.add(token);
        } else if (Domain.isHostname(token)) {
            token = Domain.normalizeHostname(token, true);
            String host = token;
            do {
                int index = host.indexOf('.') + 1;
                host = host.substring(index);
                String token2 = '.' + host;
                if (SET.contains(token2)) {
                    return token2;
                } else if (userEmail != null && SET.contains(userEmail + ':' + token2)) {
                    return userEmail + ':' + token2;
                }
                regexList.addFirst(token2);
            } while (host.contains("."));
            if (token.endsWith(".br")) {
                whoisSet.add(token);
            }
        } else {
            regexList.add(token);
        }
        try {
            // Verifica um critério do REGEX.
            String regex;
            if ((regex = REGEX.get(userEmail, regexList, autoBlock)) != null) {
                return regex;
            }
        } catch (Exception ex) {
            Server.logError(ex);
        }
        try {
            // Verifica critérios do WHOIS.
            String whois;
            if ((whois = WHOIS.get(userEmail, whoisSet, autoBlock)) != null) {
                return whois;
            }
        } catch (Exception ex) {
            Server.logError(ex);
        }
        return null;
    }
    
    public static void clear(
            Client client,
            User user,
            String ip,
            String sender,
            String hostname,
            String qualifier,
            String recipient
            ) throws ProcessException {
        String block;
        int mask = SubnetIPv4.isValidIPv4(ip) ? 32 : 64;
        if ((block = Block.clearCIDR(ip, mask)) != null) {
            if (user == null) {
                Server.logInfo("false positive BLOCK '" + block + "' detected.");
            } else {
                Server.logInfo("false positive BLOCK '" + block + "' detected by '" + user.getEmail() + "'.");
            }
        }
        TreeSet<String> blockSet = new TreeSet<String>();
        while ((block = find(client, user, ip, sender, hostname, qualifier, recipient, false)) != null) {
            if (blockSet.contains(block)) {
                throw new ProcessException("FATAL BLOCK ERROR");
            } else if (dropExact(block)) {
                if (user != null) {
                    Server.logInfo("false positive BLOCK '" + block + "' detected by '" + user.getEmail() + "'.");
                } else if (client != null && client.hasEmail()) {
                    Server.logInfo("false positive BLOCK '" + block + "' detected by '" + client.getEmail() + "'.");
                } else {
                    Server.logInfo("false positive BLOCK '" + block + "' detected.");
                }
            }
            blockSet.add(block);
        }
//        while (dropExact(block = find(client, user, ip, sender, hostname, qualifier, recipient, false))) {
//            if (blockSet.contains(block)) {
//                throw new ProcessException("FATAL BLOCK ERROR");
//            } else if (user == null) {
//                Server.logInfo("false positive BLOCK '" + block + "' detected.");
//            } else {
//                Server.logInfo("false positive BLOCK '" + block + "' detected by '" + user.getEmail() + "'.");
//            }
//            blockSet.add(block);
//        }
    }

    public static boolean contains(Client client, User user,
            String ip, String sender, String helo,
            String qualifier, String recipient, boolean autoblock
            ) throws ProcessException {
        return find(client, user, ip, sender, helo,
                qualifier, recipient, autoblock) != null;
    }

    public static String find(
            Client client,
            User user,
            String ip,
            String sender,
            String hostname,
            String qualifier,
            String recipient,
            boolean autoblock
            ) {
        // Definição do destinatário.
        String recipientDomain;
        if (recipient != null && recipient.contains("@")) {
            int index = recipient.indexOf('@');
            recipient = recipient.toLowerCase();
            if (recipient.startsWith("postmaster@")) {
                // Não pode haver bloqueio para o postmaster.
                return null;
            } else {
                recipientDomain = recipient.substring(index);
            }
        } else {
            recipient = null;
            recipientDomain = null;
        }
        if (sender == null && hostname != null) {
            sender = "mailer-daemon@" + hostname;
        }
        TreeSet<String> whoisSet = new TreeSet<String>();
        TreeSet<String> regexSet = new TreeSet<String>();
        // Definição do e-mail do usuário.
        String userEmail = null;
        if (user != null) {
            userEmail = user.getEmail();
        } else if (client != null) {
            userEmail = client.getEmail();
        }
        String found;
        if ((found = findSender(userEmail, sender, qualifier,
                recipient, recipientDomain, whoisSet, regexSet)) != null) {
            return found;
        } else if (!qualifier.equals("PASS")
                && (found = findSender(userEmail, sender, "NOTPASS",
                recipient, recipientDomain, whoisSet, regexSet)) != null) {
            return found;
        } else if ((found = findSender(userEmail, sender, ip,
                recipient, recipientDomain, whoisSet, regexSet)) != null) {
            return found;
        }
        // Verifica o HELO.
        if ((hostname = Domain.extractHost(hostname, true)) != null) {
            if ((found = findHost(userEmail, sender, hostname, qualifier,
                    recipient, recipientDomain, whoisSet, regexSet, SPF.matchHELO(ip, hostname))) != null) {
                return found;
            }
            if (hostname.endsWith(".br") && SPF.matchHELO(ip, hostname)) {
                whoisSet.add(hostname);
            }
            regexSet.add(hostname);
        }
        // Verifica o IP.
        if (ip != null) {
            ip = Subnet.normalizeIP(ip);
            String cidr;
            String dnsbl;
            if (SET.contains(ip)) {
                return ip;
            } else if (recipient != null && SET.contains(ip + '>' + recipient)) {
                return ip + '>' + recipient;
            } else if (recipientDomain != null && SET.contains(ip + '>' + recipientDomain)) {
                return ip + '>' + recipientDomain;
            } else if (SET.contains(ip + ';' + qualifier)) {
                return ip + ';' + qualifier;
            } else if (recipient != null && SET.contains(ip + ';' + qualifier + '>' + recipient)) {
                return ip + ';' + qualifier + '>' + recipient;
            } else if (recipientDomain != null && SET.contains(ip + ';' + qualifier + '>' + recipientDomain)) {
                return ip + ';' + qualifier + '>' + recipientDomain;
            } else if (userEmail != null && SET.contains(userEmail + ":@;" + ip)) {
                return userEmail + ":@;" + ip;
            } else if (userEmail != null && SET.contains(userEmail + ':' + ip)) {
                return ip;
            } else if (userEmail != null && recipient != null && SET.contains(userEmail + ':' + ip + '>' + recipient)) {
                return ip + '>' + recipient;
            } else if (userEmail != null && recipientDomain != null && SET.contains(userEmail + ':' + ip + '>' + recipientDomain)) {
                return ip + '>' + recipientDomain;
            } else if (userEmail != null && SET.contains(userEmail + ':' + ip + ';' + qualifier)) {
                return ip + ';' + qualifier;
            } else if (userEmail != null && recipient != null && SET.contains(userEmail + ':' + ip + ';' + qualifier + '>' + recipient)) {
                return ip + ';' + qualifier + '>' + recipient;
            } else if (userEmail != null && recipientDomain != null && SET.contains(userEmail + ':' + ip + ';' + qualifier + '>' + recipientDomain)) {
                return ip + ';' + qualifier + '>' + recipientDomain;
            } else if ((cidr = CIDR.get(userEmail, ip)) != null) {
                return cidr;
            } else if ((dnsbl = DNSBL.get(userEmail, ip)) != null) {
                return dnsbl;
            }
            Reverse reverse = Reverse.get(ip);
            if (reverse != null) {
                for (String host : reverse.getAddressSet()) {
                    String block = find(client, user, host, autoblock);
                    if (block != null) {
                        return block;
                    }
                }
            }
            regexSet.add(ip);
        }
        try {
            // Verifica um critério do REGEX.
            String regex;
            if ((regex = REGEX.get(userEmail, regexSet, autoblock)) != null) {
                return regex;
            }
        } catch (Exception ex) {
            Server.logError(ex);
        }
        try {
            // Verifica critérios do WHOIS.
            String whois;
            if ((whois = WHOIS.get(userEmail, whoisSet, autoblock)) != null) {
                return whois;
            }
        } catch (Exception ex) {
            Server.logError(ex);
        }
        return null;
    }
    
    public static String findSender(
            String userEmail,
            String sender,
            String validation,
            String recipient,
            String recipientDomain,
            TreeSet<String> whoisSet,
            TreeSet<String> regexSet
    ) {
        // Verifica o remetente.
        if (sender != null && sender.contains("@")) {
            sender = sender.toLowerCase();
            if (sender.startsWith("srs0=")) {
                int index = sender.lastIndexOf('@');
                String senderOriginal = sender.substring(0, index);
                StringTokenizer tokenizer = new StringTokenizer(senderOriginal, "=");
                if (tokenizer.countTokens() == 5) {
                    tokenizer.nextToken();
                    tokenizer.nextToken();
                    tokenizer.nextToken();
                    String senderDomain = tokenizer.nextToken();
                    String part = tokenizer.nextToken();
                    senderOriginal = part + '@' + senderDomain;
                    String block = Block.find(userEmail, senderOriginal, false);
                    if (block != null) {
                        return block;
                    }
                }
            }
            int index1 = sender.indexOf('@');
            int index2 = sender.lastIndexOf('@');
            String part = sender.substring(0, index1 + 1);
            String senderDomain = sender.substring(index2);
            String host;
            if (SET.contains(sender)) {
                return sender;
            } else if (SET.contains(sender + ';' + validation + '>' + recipient)) {
                return sender + ';' + validation + '>' + recipient;
            } else if (SET.contains(sender + ';' + validation + '>' + recipientDomain)) {
                return sender + ';' + validation + '>' + recipientDomain;
            } else if (SET.contains(sender + ';' + validation)) {
                return sender + ';' + validation;
            } else if (SET.contains(sender + ';' + validation + '>' + recipient)) {
                return sender + ';' + validation + '>' + recipient;
            } else if (SET.contains(sender + ';' + validation + '>' + recipientDomain)) {
                return sender + ';' + validation + '>' + recipientDomain;
            } else if (userEmail != null && SET.contains(userEmail + ':' + sender)) {
                return userEmail + ':' + sender;
            } else if (userEmail != null && SET.contains(userEmail + ':' + sender + '>' + recipient)) {
                return userEmail + ':' + sender + '>' + recipient;
            } else if (userEmail != null && SET.contains(userEmail + ':' + sender + '>' + recipientDomain)) {
                return userEmail + ':' + sender + '>' + recipientDomain;
            } else if (userEmail != null && SET.contains(userEmail + ':' + sender + ';' + validation)) {
                return userEmail + ':' + sender + ';' + validation;
            } else if (userEmail != null && SET.contains(userEmail + ':' + sender + ';' + validation + '>' + recipient)) {
                return userEmail + ':' + sender + ';' + validation + '>' + recipient;
            } else if (userEmail != null && SET.contains(userEmail + ':' + sender + ';' + validation + '>' + recipientDomain)) {
                return userEmail + ':' + sender + ';' + validation + '>' + recipientDomain;
            } else if (SET.contains(part)) {
                return part;
            } else if (SET.contains(part + '>' + recipient)) {
                return part + '>' + recipient;
            } else if (SET.contains(part + '>' + recipientDomain)) {
                return part + '>' + recipientDomain;
            } else if (SET.contains(part + ';' + validation)) {
                return part + ';' + validation;
            } else if (SET.contains(part + ';' + validation + '>' + recipient)) {
                return part + ';' + validation + '>' + recipient;
            } else if (SET.contains(part + ';' + validation + '>' + recipientDomain)) {
                return part + ';' + validation + '>' + recipientDomain;
            } else if (userEmail != null && SET.contains(userEmail + ':' + part)) {
                return userEmail + ':' + part;
            } else if (userEmail != null && SET.contains(userEmail + ':' + part + '>' + recipient)) {
                return userEmail + ':' + part + '>' + recipient;
            } else if (userEmail != null && SET.contains(userEmail + ':' + part + '>' + recipientDomain)) {
                return userEmail + ':' + part + '>' + recipientDomain;
            } else if (userEmail != null && SET.contains(userEmail + ':' + part + ';' + validation)) {
                return userEmail + ':' + part + ';' + validation;
            } else if (userEmail != null && SET.contains(userEmail + ':' + part + ';' + validation + '>' + recipient)) {
                return userEmail + ':' + part + ';' + validation + '>' + recipient;
            } else if (userEmail != null && SET.contains(userEmail + ':' + part + ';' + validation + '>' + recipientDomain)) {
                return userEmail + ':' + part + ';' + validation + '>' + recipientDomain;
            } else if (SET.contains(senderDomain)) {
                return senderDomain;
            } else if (SET.contains(senderDomain + '>' + recipient)) {
                return senderDomain + '>' + recipient;
            } else if (SET.contains(senderDomain + '>' + recipientDomain)) {
                return senderDomain + '>' + recipientDomain;
            } else if (SET.contains(senderDomain + ';' + validation)) {
                return senderDomain + ';' + validation;
            } else if (SET.contains(senderDomain + ';' + validation + '>' + recipient)) {
                return senderDomain + ';' + validation + '>' + recipient;
            } else if (SET.contains(senderDomain + ';' + validation + '>' + recipientDomain)) {
                return senderDomain + ';' + validation + '>' + recipientDomain;
            } else if (userEmail != null && SET.contains(userEmail + ':' + senderDomain)) {
                return userEmail + ':' + senderDomain;
            } else if (userEmail != null && SET.contains(userEmail + ':' + senderDomain + '>' + recipient)) {
                return userEmail + ':' + senderDomain + '>' + recipient;
            } else if (userEmail != null && SET.contains(userEmail + ':' + senderDomain + '>' + recipientDomain)) {
                return userEmail + ':' + senderDomain + '>' + recipientDomain;
            } else if (userEmail != null && SET.contains(userEmail + ':' + senderDomain + ';' + validation)) {
                return userEmail + ':' + senderDomain + ';' + validation;
            } else if (userEmail != null && SET.contains(userEmail + ':' + senderDomain + ';' + validation + '>' + recipient)) {
                return userEmail + ':' + senderDomain + ';' + validation + '>' + recipient;
            } else if (userEmail != null && SET.contains(userEmail + ':' + senderDomain + ';' + validation + '>' + recipientDomain)) {
                return userEmail + ':' + senderDomain + ';' + validation + '>' + recipientDomain;
            } else if ((host = findHost(userEmail, sender, "." + senderDomain.substring(1), validation, recipient, recipientDomain, whoisSet, regexSet, false)) != null) {
                return host;
            } else if (recipient != null && SET.contains("@>" + recipient)) {
                return "@>" + recipient;
            } else if (recipientDomain != null && SET.contains("@>" + recipientDomain)) {
                return "@>" + recipientDomain;
            } else if (recipient != null && userEmail != null && SET.contains(userEmail + ":@>" + recipient)) {
                return userEmail + ":@>" + recipient;
            } else if (recipientDomain != null && userEmail != null && SET.contains(userEmail + ":@>" + recipientDomain)) {
                return userEmail + ":@>" + recipientDomain;
            } else if (recipient != null && SET.contains("@;" + validation)) {
                return "@;" + validation;
            } else if (recipient != null && SET.contains("@;" + validation + ">" + recipient)) {
                return "@;" + validation + ">" + recipient;
            } else if (recipientDomain != null && SET.contains("@;" + validation + ">" + recipientDomain)) {
                return "@;" + validation + ">" + recipientDomain;
            } else if (recipient != null && userEmail != null && SET.contains(userEmail + ":@;" + validation)) {
                return userEmail + ":@;" + validation;
            } else if (recipient != null && userEmail != null && SET.contains(userEmail + ":@;" + validation + ">" + recipient)) {
                return userEmail + ":@;" + validation +  ">" + recipient;
            } else if (recipientDomain != null && userEmail != null && SET.contains(userEmail + ":@;" + validation + ">" + recipientDomain)) {
                return userEmail + ":@;" + validation +  ">" + recipientDomain;
            } else {
                int index3 = senderDomain.length();
                while ((index3 = senderDomain.lastIndexOf('.', index3 - 1)) > index2) {
                    String subdomain = senderDomain.substring(0, index3 + 1);
                    if (SET.contains(subdomain)) {
                        return subdomain;
                    } else if (SET.contains(subdomain + '>' + recipient)) {
                        return subdomain + '>' + recipient;
                    } else if (SET.contains(subdomain + '>' + recipientDomain)) {
                        return subdomain + '>' + recipientDomain;
                    } else if (SET.contains(subdomain + ';' + validation)) {
                        return subdomain + ';' + validation;
                    } else if (SET.contains(subdomain + ';' + validation + '>' + recipient)) {
                        return subdomain + ';' + validation + '>' + recipient;
                    } else if (SET.contains(subdomain + ';' + validation + '>' + recipientDomain)) {
                        return subdomain + ';' + validation + '>' + recipientDomain;
                    } else if (userEmail != null && SET.contains(userEmail + ':' + subdomain)) {
                        return userEmail + ':' + subdomain;
                    } else if (userEmail != null && SET.contains(userEmail + ':' + subdomain + '>' + recipient)) {
                        return userEmail + ':' + subdomain + '>' + recipient;
                    } else if (userEmail != null && SET.contains(userEmail + ':' + subdomain + '>' + recipientDomain)) {
                        return userEmail + ':' + subdomain + '>' + recipientDomain;
                    } else if (userEmail != null && SET.contains(userEmail + ':' + subdomain + ';' + validation)) {
                        return userEmail + ':' + subdomain + ';' + validation;
                    } else if (userEmail != null && SET.contains(userEmail + ':' + subdomain + ';' + validation + '>' + recipient)) {
                        return userEmail + ':' + subdomain + ';' + validation + '>' + recipient;
                    } else if (userEmail != null && SET.contains(userEmail + ':' + subdomain + ';' + validation + '>' + recipientDomain)) {
                        return userEmail + ':' + subdomain + ';' + validation + '>' + recipientDomain;
                    }
                }
                int index4 = sender.length();
                while ((index4 = sender.lastIndexOf('.', index4 - 1)) > index2) {
                    String subsender = sender.substring(0, index4 + 1);
                    if (SET.contains(subsender)) {
                        return subsender;
                    } else if (SET.contains(subsender + '>' + recipient)) {
                        return subsender + '>' + recipient;
                    } else if (SET.contains(subsender + '>' + recipientDomain)) {
                        return subsender + '>' + recipientDomain;
                    } else if (SET.contains(subsender + ';' + validation)) {
                        return subsender + ';' + validation;
                    } else if (SET.contains(subsender + ';' + validation + '>' + recipient)) {
                        return subsender + ';' + validation + '>' + recipient;
                    } else if (SET.contains(subsender + ';' + validation + '>' + recipientDomain)) {
                        return subsender + ';' + validation + '>' + recipientDomain;
                    } else if (userEmail != null && SET.contains(userEmail + ':' + subsender)) {
                        return userEmail + ':' + subsender;
                    } else if (userEmail != null && SET.contains(userEmail + ':' + subsender + '>' + recipient)) {
                        return userEmail + ':' + subsender + '>' + recipient;
                    } else if (userEmail != null && SET.contains(userEmail + ':' + subsender + '>' + recipientDomain)) {
                        return userEmail + ':' + subsender + '>' + recipientDomain;
                    } else if (userEmail != null && SET.contains(userEmail + ':' + subsender + ';' + validation)) {
                        return userEmail + ':' + subsender + ';' + validation;
                    } else if (userEmail != null && SET.contains(userEmail + ':' + subsender + ';' + validation + '>' + recipient)) {
                        return userEmail + ':' + subsender + ';' + validation + '>' + recipient;
                    } else if (userEmail != null && SET.contains(userEmail + ':' + subsender + ';' + validation + '>' + recipientDomain)) {
                        return userEmail + ':' + subsender + ';' + validation + '>' + recipientDomain;
                    }
                }
            }
            if (senderDomain.endsWith(".br")) {
                whoisSet.add(senderDomain);
            }
            regexSet.add(sender);
            regexSet.add(senderDomain);
        }
        return null;
    }
    
    public static boolean containsCIDR(String ip) {
        if ((ip = Subnet.normalizeIP(ip)) == null) {
            return false;
        } else {
            return CIDR.get(null, ip) != null;
        }
    }
    
    public static boolean containsDNSBL(String ip) {
        if ((ip = Subnet.normalizeIP(ip)) == null) {
            return false;
        } else {
            return DNSBL.get(null, ip) != null;
        }
    }

    private static int parseIntWHOIS(String value) {
        try {
            if (value == null || value.length() == 0) {
                return 0;
            } else {
                Date date = Domain.DATE_FORMATTER.parse(value);
                long time = date.getTime() / (1000 * 60 * 60 * 24);
                long today = System.currentTimeMillis() / (1000 * 60 * 60 * 24);
                return (int) (today - time);
            }
        } catch (Exception ex) {
            try {
                return Integer.parseInt(value);
            } catch (Exception ex2) {
                return 0;
            }
        }
    }
    
    public static boolean containsDomain(String host) {
        return containsDomain(null, host);
    }
    
    public static boolean containsDomainIP(String host, String ip) {
        if (containsCIDR(ip)) {
            return true;
        } else if (containsDomain(null, host)) {
            return true;
        } else {
            return false;
        }
    }
    
    public static boolean containsDomain(String client, String host) {
        host = Domain.extractHost(host, true);
        if (host == null) {
            return false;
        } else {
            do {
                int index = host.indexOf('.') + 1;
                host = host.substring(index);
                String token = '.' + host;
                if (SET.contains(token)) {
                    return true;
                } else if (client != null && SET.contains(client + ':' + token)) {
                    return true;
                }
            } while (host.contains("."));
            return false;
        }
    }
    
    public static boolean containsREGEX(
            String host
            ) throws ProcessException {
        host = Domain.extractHost(host, true);
        if (host == null) {
            return false;
        } else {
            LinkedList<String> tokenList = new LinkedList<String>();
            do {
                int index = host.indexOf('.') + 1;
                host = host.substring(index);
                String token = '.' + host;
                tokenList.addFirst(token);
            } while (host.contains("."));
            return REGEX.get(null, tokenList, true) != null;
        }
    }
    
    public static boolean containsWHOIS(
            String host
            ) throws ProcessException {
        host = Domain.extractHost(host, true);
        if (host == null) {
            return false;
        } else if (host.endsWith(".br")) {
            try {
                TreeSet<String> tokenSet = new TreeSet<String>();
                tokenSet.add(host);
                return WHOIS.get(null, tokenSet, true) != null;
            } catch (Exception ex) {
                Server.logError(ex);
                return false;
            }
        } else {
            return false;
        }
    }

    private static String findHost(String userEmail, String sender,
            String hostname, String qualifier, String recipient,
            String recipientDomain, TreeSet<String> whoisSet,
            TreeSet<String> regexSet, boolean full) {
        hostname = Domain.extractHost(hostname, true);
        if (hostname == null) {
            return null;
        } else {
            do {
                int index = hostname.indexOf('.') + 1;
                hostname = hostname.substring(index);
                String token = '.' + hostname;
                if (SET.contains(token)) {
                    return token;
                } else if (SET.contains(token + '>' + recipient)) {
                    return token + '>' + recipient;
                } else if (SET.contains(token + '>' + recipientDomain)) {
                    return token + '>' + recipientDomain;
                } else if (SET.contains(token + ';' + qualifier)) {
                    return token + ';' + qualifier;
                } else if (SET.contains(token + ';' + qualifier + '>' + recipient)) {
                    return token + ';' + qualifier + '>' + recipient;
                } else if (SET.contains(token + ';' + qualifier + '>' + recipientDomain)) {
                    return token + ';' + qualifier + '>' + recipientDomain;
                } else if (userEmail != null && SET.contains(userEmail + ":@;" + hostname)) {
                    return userEmail + ":@;" + hostname;
                } else if (userEmail != null && SET.contains(userEmail + ':' + token)) {
                    return userEmail + ':' + token;
                } else if (userEmail != null  && SET.contains(userEmail + ':' + token + '>' + recipient)) {
                    return userEmail + ':' + token + '>' + recipient;
                } else if (userEmail != null  && SET.contains(userEmail + ':' + token + '>' + recipientDomain)) {
                    return userEmail + ':' + token + '>' + recipientDomain;
                } else if (userEmail != null  && SET.contains(userEmail + ':' + token + ';' + qualifier)) {
                    return userEmail + ':' + token + ';' + qualifier;
                } else if (userEmail != null  && SET.contains(userEmail + ':' + token + ';' + qualifier + '>' + recipient)) {
                    return userEmail + ':' + token + ';' + qualifier + '>' + recipient;
                } else if (userEmail != null  && SET.contains(userEmail + ':' + token + ';' + qualifier + '>' + recipientDomain)) {
                    return userEmail + ':' + token + ';' + qualifier + '>' + recipientDomain;
                } else if (full && (token = findSender(userEmail, sender, hostname, recipient,
                        recipientDomain, whoisSet, regexSet)) != null) {
                    return token;
                }
            } while (hostname.contains("."));
            return null;
        }
    }

    public static void store(boolean simplify) {
        if (CHANGED) {
            try {
                Server.logTrace("simplifing block.set");
                if (simplify) {
                    CIDR.simplify();
                }
                Server.logTrace("storing block.set");
                long time = System.currentTimeMillis();
                File file = new File("./data/block.set");
//                TreeSet<String> genericSet = new TreeSet<String>();
//                TreeSet<String> tokenSet = new TreeSet<String>();
//                for (String token : getAll()) {
//                    if (simplify
//                            && genericSet.size() < 100
//                            && Domain.isHostname(token)
//                            && Generic.contains(token)
//                            ) {
//                        // Até final da transição.
//                        genericSet.add(token);
//                        Server.logDebug("BLOCK '" + token + "' removed by GENERIC.");
//                        Block.dropExact(token);
//                    } else {
//                        tokenSet.add(token);
//                    }
//                }
//                for (String token : genericSet) {
//                    String regex = Block.REGEX.find(token);
//                    if (Block.REGEX.dropExact(regex)) {
//                        Server.logDebug("BLOCK '" + regex + "' removed by GENERIC.");
//                    }
//                }
                TreeSet<String> tokenSet = getAll();
                FileOutputStream outputStream = new FileOutputStream(file);
                try {
                    SerializationUtils.serialize(tokenSet, outputStream);
                    CHANGED = false;
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
        File file = new File("./data/block.set");
        if (file.exists()) {
            try {
                Set<String> set;
                FileInputStream fileInputStream = new FileInputStream(file);
                try {
                    set = SerializationUtils.deserialize(fileInputStream);
                } finally {
                    fileInputStream.close();
                }
                for (String token : set) {
                    String client;
                    String identifier;
                    if (token.contains(":")) {
                        int index = token.indexOf(':');
                        client = token.substring(0, index);
                        identifier = token.substring(index + 1);
                    } else {
                        client = null;
                        identifier = token;
                    }
                    if (identifier.startsWith("CIDR=")) {
                        CIDR.addExact(client, identifier);
                    } else if (token.startsWith("WHOIS/")) {
                        WHOIS.addExact(client, token);
                    } else if (token.startsWith("DNSBL=")) {
                        DNSBL.addExact(client, token);
                    } else if (token.startsWith("REGEX=")) {
                        REGEX.addExact(client, token);
                    } else {
                        SET.addExact(token);
                    }
                }
                CHANGED = false;
                Server.logLoad(time, file);
            } catch (Exception ex) {
                Server.logError(ex);
            }
        }
    }
}
