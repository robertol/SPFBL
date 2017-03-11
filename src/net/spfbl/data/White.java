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

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.spfbl.core.Client;
import net.spfbl.core.Core;
import net.spfbl.core.Peer;
import net.spfbl.core.ProcessException;
import net.spfbl.core.Server;
import net.spfbl.core.User;
import net.spfbl.spf.SPF;
import net.spfbl.whois.Domain;
import net.spfbl.whois.Owner;
import net.spfbl.whois.Subnet;
import net.spfbl.whois.SubnetIPv4;
import net.spfbl.whois.SubnetIPv6;
import org.apache.commons.lang3.SerializationUtils;

/**
 * Representa a lista de liberação do sistema.
 * 
 * @author Leandro Carlos Rodrigues <leandro@spfbl.net>
 */
public class White {
    
    /**
     * Flag que indica se o cache foi modificado.
     */
    private static boolean CHANGED = false;
    
    /**
     * Conjunto de remetentes liberados.
     */
    private static class SET {
        
        private static final HashSet<String> SET = new HashSet<String>();
        
        public static synchronized boolean isEmpty() {
            return SET.isEmpty();
        }
        
        public static synchronized TreeSet<String> clear() {
            TreeSet<String> set = new TreeSet<String>();
            set.addAll(SET);
            SET.clear();
            return set;
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
    
    /**
     * Conjunto de critérios WHOIS para liberação.
     */
    private static class WHOIS {
        
        private static final HashMap<String,TreeSet<String>> MAP = new HashMap<String,TreeSet<String>>();
        
        public static synchronized boolean isEmpty() {
            return MAP.isEmpty();
        }
        
        public static synchronized TreeSet<String> clear() {
            TreeSet<String> set = getAll();
            MAP.clear();
            return set;
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
        
        public static boolean contains(String client, String dnsbl) {
            if (dnsbl == null) {
                return false;
            } else {
                TreeSet<String> dnsblSet = getClientSet(client);
                if (dnsblSet == null) {
                    return false;
                } else {
                    return dnsblSet.contains(dnsbl);
                }
            }
        }
        
        private static String[] getArray(String client) {
            TreeSet<String> set = getClientSet(client);
            if (set == null) {
                return null;
            } else {
                int size = set.size();
                String[] array = new String[size];
                return set.toArray(array);
            }
        }
        
        private static String get(String client, Set<String> tokenSet) {
            if (tokenSet.isEmpty()) {
                return null;
            } else {
                TreeSet<String> subSet = new TreeSet<String>();
                String[] array = getArray(null);
                if (array != null) {
                    subSet.addAll(Arrays.asList(array));
                }
                if (client != null) {
                    array = getArray(client);
                    if (array != null) {
                        for (String whois : array) {
                            subSet.add(client + ':' + whois);
                        }
                    }
                }
                if (subSet.isEmpty()) {
                    return null;
                } else {
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
                                int indexUser = whois.indexOf(':');
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
                                                return whois;
                                            }
                                        } else if (value.length() > 0) {
                                            int criterionInt = parseIntWHOIS(criterion);
                                            int valueInt = parseIntWHOIS(value);
                                            if (signal == '<' && valueInt < criterionInt) {
                                                return whois;
                                            } else if (signal == '>' && valueInt > criterionInt) {
                                                return whois;
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
     * Conjunto de REGEX para liberação.
     */
    private static class REGEX {
        
        private static final HashMap<String,ArrayList<Pattern>> MAP = new HashMap<String,ArrayList<Pattern>>();
        
        public static synchronized boolean isEmpty() {
            return MAP.isEmpty();
        }
        
        public static synchronized TreeSet<String> clear() {
            TreeSet<String> set = getAll();
            MAP.clear();
            return set;
        }
        
        public static synchronized TreeSet<String> getAll() {
            TreeSet<String> set = new TreeSet<String>();
            for (String client : MAP.keySet()) {
                for (Pattern pattern : MAP.get(client)) {
                    if (client == null) {
                        set.add("REGEX=" + pattern);
                    } else {
                        set.add(client + ":REGEX=" + pattern);
                    }
                }
            }
            return set;
        }
        
        private static synchronized boolean dropExact(String token) {
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
                return false;
            } else {
                for (index = 0; index < list.size(); index++) {
                    Pattern pattern = list.get(index);
                    if (regex.equals(pattern.pattern())) {
                        list.remove(index);
                        if (list.isEmpty()) {
                            MAP.remove(client);
                        }
                        return true;
                    }
                }
                return false;
            }
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
        
        private static synchronized ArrayList<Pattern> getClientList(String client) {
            return MAP.get(client);
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
        
        private static Pattern[] getArray(String client) {
            ArrayList<Pattern> patternList = getClientList(client);
            if (patternList == null) {
                return null;
            } else {
                int size = patternList.size();
                Pattern[] array = new Pattern[size];
                return patternList.toArray(array);
            }
        }
        
        private static String get(String client, Set<String> tokenSet) {
            if (tokenSet.isEmpty()) {
                return null;
            } else {
//                long time = System.currentTimeMillis();
                String result = null;
                Pattern[] patternArray = getArray(null);
                if (patternArray != null) {
                    for (Pattern pattern : patternArray) {
                        for (String token : tokenSet) {
                            if (token.contains("@") == pattern.pattern().contains("@")) {
                                Matcher matcher = pattern.matcher(token);
                                if (matcher.matches()) {
                                    result = "REGEX=" + pattern.pattern();
                                    break;
                                }
                            }
                        }
                    }
                }
                if (result == null && client != null) {
                    patternArray = getArray(client);
                    if (patternArray != null) {
                        for (Pattern pattern : patternArray) {
                            for (String token : tokenSet) {
                                if (token.contains("@") == pattern.pattern().contains("@")) {
                                    Matcher matcher = pattern.matcher(token);
                                    if (matcher.matches()) {
                                        result = client + ":REGEX=" + pattern.pattern();
                                        break;
                                    }
                                }
                            }
                        }
                    }
                }
//                logTrace(time, "REGEX lookup for " + tokenSet + ".");
                return result;
            }
        }
    }
    
    /**
     * Representa o conjunto de blocos IP liberados.
     */
    private static class CIDR {
        
        private static final HashMap<String,TreeSet<String>> MAP = new HashMap<String,TreeSet<String>>();
        
        public static synchronized boolean isEmpty() {
            return MAP.isEmpty();
        }
        
        public static synchronized TreeSet<String> clear() {
            TreeSet<String> set = getAll();
            MAP.clear();
            return set;
        }
        
        public static synchronized TreeSet<String> getAll() {
            TreeSet<String> set = new TreeSet<String>();
            for (String client : MAP.keySet()) {
                for (String cidr : MAP.get(client)) {
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
            return set;
        }
        
        private static synchronized boolean dropExact(String token) {
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
        
        private static synchronized boolean addExact(String token) throws ProcessException {
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
            String first = Subnet.getFirstIP(cidr);
            String last = Subnet.getLastIP(cidr);
            String floorLower = set.lower(key);
            String floorLast = set.floor(Subnet.expandIP(last) + "/9");
            if (floorLower == null) {
                floorLower = null;
            } else if (floorLower.contains(".")) {
                floorLower = SubnetIPv4.normalizeCIDRv4(floorLower);
            } else if (floorLower.contains(":")) {
                floorLower = SubnetIPv6.normalizeCIDRv6(floorLower);
            } else {
                floorLower = null;
            }
            if (floorLast == null) {
                floorLast = null;
            } else if (floorLast.contains(".")) {
                floorLast = SubnetIPv4.normalizeCIDRv4(floorLast);
            } else if (floorLast.contains(":")) {
                floorLast = SubnetIPv6.normalizeCIDRv6(floorLast);
            } else {
                floorLast = null;
            }
            if (cidr.equals(floorLast)) {
                return false;
            } else if (Subnet.containsIP(floorLast, first)) {
                throw new ProcessException("INTERSECTS " + floorLast);
            } else if (Subnet.containsIP(floorLast, last)) {
                throw new ProcessException("INTERSECTS " + floorLast);
            } else if (Subnet.containsIP(floorLower, first)) {
                throw new ProcessException("INTERSECTS " + floorLower);
            } else if (Subnet.containsIP(floorLower, last)) {
                throw new ProcessException("INTERSECTS " + floorLower);
            } else if (Subnet.containsIP(cidr, Subnet.getFirstIP(floorLast))) {
                throw new ProcessException("INTERSECTS " + floorLast);
            } else if (Subnet.containsIP(cidr, Subnet.getLastIP(floorLast))) {
                throw new ProcessException("INTERSECTS " + floorLast);
            } else {
                return set.add(key);
            }
        }
        
        private static synchronized TreeSet<String> getClientSet(String client) {
            return MAP.get(client);
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
            return result;
        }
    }

    public static boolean dropExact(String token) throws ProcessException {
        if (token == null) {
            return false;
        } else if (token.contains("WHOIS/")) {
            if (WHOIS.dropExact(token)) {
                Peer.releaseAll(token);
                CHANGED = true;
                return true;
            } else {
                return false;
            }
        } else if (token.contains("CIDR=")) {
            if (CIDR.dropExact(token)) {
                Peer.releaseAll(token);
                CHANGED = true;
                return true;
            } else {
                return false;
            }
        } else if (token.contains("REGEX=")) {
            if (REGEX.dropExact(token)) {
                Peer.releaseAll(token);
                CHANGED = true;
                return true;
            } else {
                return false;
            }
        } else if (SET.dropExact(token)) {
            Peer.releaseAll(token);
            CHANGED = true;
            return true;
        } else {
            return false;
        }
    }

    public static synchronized TreeSet<String> dropAll() {
        TreeSet<String> set = SET.clear();
        set.addAll(CIDR.clear());
        set.addAll(REGEX.clear());
        set.addAll(WHOIS.clear());
        CHANGED = true;
        return set;
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
        } else if (token.contains("CIDR=")) {
            if (CIDR.addExact(token)) {
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

    public static synchronized TreeSet<String> getAll() throws ProcessException {
        TreeSet<String> whiteSet = SET.getAll();
        whiteSet.addAll(CIDR.getAll());
        whiteSet.addAll(REGEX.getAll());
        whiteSet.addAll(WHOIS.getAll());
        return whiteSet;
    }
    
    public static boolean containsIP(String ip) {
        if ((ip = Subnet.normalizeIP(ip)) == null) {
            return false;
        } else {
            return CIDR.get(null, ip) != null;
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
    
    private static boolean matches(String regex, String token) {
        try {
            return Pattern.matches(regex, token);
        } catch (Exception ex) {
            return false;
        }
    }
    
    private static boolean isWHOIS(String token) {
        return matches("^WHOIS(/[a-z-]+)+((=[a-zA-Z0-9@/.-]+)|((<|>)[0-9]+))$", token);
    }

    private static boolean isREGEX(String token) {
        return matches("^REGEX=[^ ]+$", token);
    }
    
    private static boolean isDNSBL(String token) {
        if (token.startsWith("DNSBL=") && token.contains(";")) {
            int index1 = token.indexOf('=');
            int index2 = token.indexOf(';');
            String server = token.substring(index1 + 1, index2);
            String value = token.substring(index2 + 1);
            return Domain.isHostname(server) && Subnet.isValidIP(value);
        } else {
            return false;
        }
    }

    private static boolean isCIDR(String token) {
        return matches("^CIDR=("
                + "((([0-9]|[1-9][0-9]|1[0-9]{2}|2[0-4][0-9]|25[0-5])\\.){3}"
                + "([0-9]|[1-9][0-9]|1[0-9]{2}|2[0-4][0-9]|25[0-5])/[0-9]{1,2})"
                + "|"
                + "(([0-9a-fA-F]{1,4}:){7,7}[0-9a-fA-F]{1,4}|"
                + "([0-9a-fA-F]{1,4}:){1,7}:|"
                + "([0-9a-fA-F]{1,4}:){1,6}:[0-9a-fA-F]{1,4}|"
                + "([0-9a-fA-F]{1,4}:){1,5}(:[0-9a-fA-F]{1,4}){1,2}|"
                + "([0-9a-fA-F]{1,4}:){1,4}(:[0-9a-fA-F]{1,4}){1,3}|"
                + "([0-9a-fA-F]{1,4}:){1,3}(:[0-9a-fA-F]{1,4}){1,4}|"
                + "([0-9a-fA-F]{1,4}:){1,2}(:[0-9a-fA-F]{1,4}){1,5}|"
                + "[0-9a-fA-F]{1,4}:((:[0-9a-fA-F]{1,4}){1,6})|"
                + ":((:[0-9a-fA-F]{1,4}){1,7}|:)|"
                + "fe80:(:[0-9a-fA-F]{0,4}){0,4}%[0-9a-zA-Z]{1,}|"
                + "::(ffff(:0{1,4}){0,1}:){0,1}"
                + "((25[0-5]|(2[0-4]|1{0,1}[0-9]){0,1}[0-9])\\.){3,3}"
                + "(25[0-5]|(2[0-4]|1{0,1}[0-9]){0,1}[0-9])|"
                + "([0-9a-fA-F]{1,4}:){1,4}:"
                + "((25[0-5]|(2[0-4]|1{0,1}[0-9]){0,1}[0-9])\\.){3,3}"
                + "(25[0-5]|(2[0-4]|1{0,1}[0-9]){0,1}[0-9])/[0-9]{1,3})"
                + ")$", token);
    }
    
    public static String normalizeTokenWhite(String token) throws ProcessException {
        token = SPF.normalizeToken(token, true, true, true, false,
//                true,
                false);
        if (token == null) {
            return null;
        } else if (token.contains(";PASS")) {
            return token;
//        } else if (token.contains(";FAIL")) {
//            return token;
        } else if (token.contains(";SOFTFAIL")) {
            return token;
        } else if (token.contains(";NEUTRAL")) {
            return token;
        } else if (token.contains(";NONE")) {
            return token;
        } else if (isWHOIS(token)) {
            return token;
        } else if (isREGEX(token)) {
            return token;
        } else if (isDNSBL(token)) {
            return token;
        } else if (isCIDR(token)) {
            return token;
        } else if (token.startsWith("@>")) {
            return token;
        } else if (token.contains(">")) {
            int index = token.indexOf('>');
            return token.substring(0, index) + ";PASS" + token.substring(index);
        } else if (token.contains(";")) {
            return token;
        } else {
            return token + ";PASS";
        }
    }

    public static boolean add(
            String sender) throws ProcessException {
        if ((sender = normalizeTokenWhite(sender)) == null) {
            throw new ProcessException("ERROR: SENDER INVALID");
        } else if (addExact(sender)) {
            return true;
        } else {
            return false;
        }
    }

    public static boolean add(String client, String token) throws ProcessException {
        if (client == null || !Domain.isEmail(client)) {
            throw new ProcessException("CLIENT INVALID");
        } else if ((token = normalizeTokenWhite(token)) == null) {
            throw new ProcessException("TOKEN INVALID");
        } else {
            return addExact(client.toLowerCase() + ':' + token);
        }
    }
    
    public static boolean add(User user, String token) throws ProcessException {
        if (user == null) {
            throw new ProcessException("USER INVALID");
        } else if ((token = normalizeTokenWhite(token)) == null) {
            throw new ProcessException("TOKEN INVALID");
        } else {
            return addExact(user.getEmail() + ':' + token);
        }
    }
    
    public static boolean add(Client client, String token) throws ProcessException {
        if (client == null || !client.hasEmail()) {
            throw new ProcessException("CLIENT INVALID");
        } else if ((token = normalizeTokenWhite(token)) == null) {
            throw new ProcessException("TOKEN INVALID");
        } else {
            return addExact(client.getEmail() + ':' + token);
        }
    }

    public static boolean drop(String token) throws ProcessException {
        if ((token = normalizeTokenWhite(token)) == null) {
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
        } else if ((token = normalizeTokenWhite(token)) == null) {
            throw new ProcessException("TOKEN INVALID");
        } else {
            return dropExact(client + ':' + token);
        }
    }
    
    public static boolean drop(User user, String token) throws ProcessException {
        if (user == null) {
            throw new ProcessException("USER INVALID");
        } else if ((token = normalizeTokenWhite(token)) == null) {
            throw new ProcessException("TOKEN INVALID");
        } else {
            return dropExact(user.getEmail() + ':' + token);
        }
    }

    public static boolean drop(Client client, String token) throws ProcessException {
        if (client == null || !client.hasEmail()) {
            throw new ProcessException("CLIENT INVALID");
        } else if ((token = normalizeTokenWhite(token)) == null) {
            throw new ProcessException("TOKEN INVALID");
        } else {
            return dropExact(client.getEmail() + ':' + token);
        }
    }
    
    public static boolean containsExtact(User user, String token) {
        if (user == null || token == null) {
            return false;
        } else {
            return SET.contains(user.getEmail() + ":" + token);
        }
    }

    public static TreeSet<String> get(Client client, User user) throws ProcessException {
        TreeSet<String> whiteSet = new TreeSet<String>();
        // Definição do e-mail do usuário.
        String userEmail = null;
        if (user != null) {
            userEmail = user.getEmail();
        } else if (client != null) {
            userEmail = client.getEmail();
        }
        if (userEmail != null) {
            for (String token : getAll()) {
                if (token.startsWith(userEmail + ':')) {
                    int index = token.indexOf(':') + 1;
                    token = token.substring(index);
                    whiteSet.add(token);
                }
            }
        }
        return whiteSet;
    }

    public static TreeSet<String> getAll(Client client, User user) throws ProcessException {
        TreeSet<String> whiteSet = new TreeSet<String>();
        // Definição do e-mail do usuário.
        String userEmail = null;
        if (user != null) {
            userEmail = user.getEmail();
        } else if (client != null) {
            userEmail = client.getEmail();
        }
        if (userEmail != null) {
            for (String token : getAll()) {
                if (!token.contains(":")) {
                    whiteSet.add(token);
                } else if (token.startsWith(userEmail + ':')) {
                    int index = token.indexOf(':') + 1;
                    token = token.substring(index);
                    whiteSet.add(token);
                }
            }
        }
        return whiteSet;
    }

    public static TreeSet<String> getAllTokens(String value) {
        TreeSet<String> whiteSet = new TreeSet<String>();
        if (Subnet.isValidIP(value)) {
            String ip = Subnet.normalizeIP(value);
            if (SET.contains(ip)) {
                whiteSet.add(ip);
            }
        } else if (Subnet.isValidCIDR(value)) {
            String cidr = Subnet.normalizeCIDR(value);
            if (CIDR.contains(null, cidr)) {
                whiteSet.add(cidr);
            }
            TreeSet<String> set = SET.getAll();
            for (String ip : set) {
                if (Subnet.containsIP(cidr, ip)) {
                    whiteSet.add(ip);
                }
            }
            for (String ip : set) {
                if (SubnetIPv6.containsIP(cidr, ip)) {
                    whiteSet.add(ip);
                }
            }
        } else if (value.startsWith(".")) {
            String hostname = value;
            TreeSet<String> set = SET.getAll();
            for (String key : set) {
                if (key.endsWith(hostname)) {
                    whiteSet.add(key);
                }
            }
            for (String mx : set) {
                String hostKey = '.' + mx.substring(1);
                if (hostKey.endsWith(hostname)) {
                    whiteSet.add(hostKey);
                }
            }
        } else if (SET.contains(value)) {
            whiteSet.add(value);
        }
        return whiteSet;
    }

    public static TreeSet<String> get() throws ProcessException {
        TreeSet<String> whiteSet = new TreeSet<String>();
        for (String token : getAll()) {
            if (!token.contains(":")) {
                whiteSet.add(token);
            }
        }
        return whiteSet;
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
        TreeSet<String> whiteSet = new TreeSet<String>();
        String white;
        while ((white = find(client, user, ip, sender, hostname, qualifier, recipient)) != null) {
            if (whiteSet.contains(white)) {
                throw new ProcessException("FATAL BLOCK ERROR");
            } else if (dropExact(white)) {
                if (user != null) {
                    Server.logInfo("false negative WHITE '" + white + "' detected by '" + user.getEmail() + "'.");
                } else if (client != null && client.hasEmail()) {
                    Server.logInfo("false negative WHITE '" + white + "' detected by '" + client.getEmail() + "'.");
                } else {
                    Server.logInfo("false negative WHITE '" + white + "' detected.");
                }
            }
            whiteSet.add(white);
        }
//        while (dropExact(white = find(client, user, ip, sender, hostname, qualifier, recipient))) {
//            if (whiteSet.contains(white)) {
//                throw new ProcessException("FATAL WHITE ERROR");
//            } else if (user == null) {
//                Server.logDebug("false negative WHITE '" + white + "' detected.");
//            } else {
//                Server.logDebug("false negative WHITE '" + white + "' detected by '" + user.getEmail() + "'.");
//            }
//            whiteSet.add(white);
//        }
    }
    
    public static boolean contains(Client client, User user,
            String ip, String sender, String hostname,
            String qualifier, String recipient) {
        return find(client, user, ip, sender, hostname, qualifier, recipient) != null;
    }
    
    public static String find(
            Client client, User user,
            String ip, String sender, String hostname,
            String qualifier, String recipient
    ) {
        TreeSet<String> whoisSet = new TreeSet<String>();
        TreeSet<String> regexSet = new TreeSet<String>();
        // Definição do e-mail do usuário.
        String userEmail = null;
        if (user != null) {
            userEmail = user.getEmail();
        } else if (client != null) {
            userEmail = client.getEmail();
        }
        // Definição do destinatário.
        String recipientDomain;
        if (recipient != null && recipient.contains("@")) {
            int index = recipient.indexOf('@');
            recipient = recipient.toLowerCase();
            recipientDomain = recipient.substring(index);
        } else {
            recipient = null;
            recipientDomain = null;
        }
        if (sender == null && hostname != null) {
            sender = "mailer-daemon@" + hostname;
        }
        String found;
        if ((found = findSender(userEmail, sender, qualifier,
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
            if (SET.contains(ip + ';' + qualifier)) {
                return ip + ';' + qualifier;
            } else if (recipient != null && SET.contains(ip + ';' + qualifier + '>' + recipient)) {
                return ip + ';' + qualifier + '>' + recipient;
            } else if (recipientDomain != null && SET.contains(ip + ';' + qualifier + '>' + recipientDomain)) {
                return ip + ';' + qualifier + '>' + recipientDomain;
            } else if (userEmail != null && SET.contains(userEmail + ":@;" + ip)) {
                return userEmail + ":@;" + ip;
            } else if (userEmail != null && SET.contains(userEmail + ':' + ip + ';' + qualifier)) {
                return userEmail + ':' + ip + ';' + qualifier;
            } else if (userEmail != null && recipient != null && SET.contains(userEmail + ':' + ip + ';' + qualifier + '>' + recipient)) {
                return userEmail + ':' + ip + ';' + qualifier + '>' + recipient;
            } else if (userEmail != null && recipientDomain != null && SET.contains(userEmail + ':' + ip + ';' + qualifier + '>' + recipientDomain)) {
                return userEmail + ':' + ip + ';' + qualifier + '>' + recipientDomain;
            } else if ((cidr = CIDR.get(userEmail, ip)) != null) {
                return cidr;
            }
            regexSet.add(ip);
        }
        // Verifica um critério do REGEX.
        String regex;
        if ((regex = REGEX.get(userEmail, regexSet)) != null) {
            return regex;
        }
        // Verifica critérios do WHOIS.
        String whois;
        if ((whois = WHOIS.get(userEmail, whoisSet)) != null) {
            return whois;
        }
        return null;
    }
    
    private static String findSender(
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
            int index1 = sender.indexOf('@');
            int index2 = sender.lastIndexOf('@');
            String part = sender.substring(0, index1 + 1);
            String senderDomain = sender.substring(index2);
            String found;
            if (senderDomain.equals("@spfbl.net") && validation.equals("PASS")) {
                return "@spfbl.net;PASS";
            } else if (sender.equals(Core.getAdminEmail()) && validation.equals("PASS")) {
                return Core.getAdminEmail() + ";PASS";
            } else if (recipient != null && SET.contains(sender + ';' + validation + '>' + recipient)) {
                return sender + ';' + validation + '>' + recipient;
            } else if (recipientDomain != null && SET.contains(sender + ';' + validation + '>' + recipientDomain)) {
                return sender + ';' + validation + '>' + recipientDomain;
            } else if (SET.contains(sender + ';' + validation)) {
                return sender + ';' + validation;
            } else if (recipient != null && SET.contains(sender + ';' + validation + '>' + recipient)) {
                return sender + ';' + validation + '>' + recipient;
            } else if (recipientDomain != null && SET.contains(sender + ';' + validation + '>' + recipientDomain)) {
                return sender + ';' + validation + '>' + recipientDomain;
            } else if (userEmail != null && SET.contains(userEmail + ':' + sender + ';' + validation)) {
                return userEmail + ':' + sender + ';' + validation;
            } else if (userEmail != null && recipient != null && SET.contains(userEmail + ':' + sender + ';' + validation + '>' + recipient)) {
                return userEmail + ':' + sender + ';' + validation + '>' + recipient;
            } else if (userEmail != null && recipientDomain != null && SET.contains(userEmail + ':' + sender + ';' + validation + '>' + recipientDomain)) {
                return userEmail + ':' + sender + ';' + validation + '>' + recipientDomain;
            } else if (SET.contains(part + ';' + validation)) {
                return part + ';' + validation;
            } else if (recipient != null && SET.contains(part + ';' + validation + '>' + recipient)) {
                return part + ';' + validation + '>' + recipient;
            } else if (recipientDomain != null && SET.contains(part + ';' + validation + '>' + recipientDomain)) {
                return part + ';' + validation + '>' + recipientDomain;
            } else if (userEmail != null && SET.contains(userEmail + ':' + part + ';' + validation)) {
                return userEmail + ':' + part + ';' + validation;
            } else if (userEmail != null && recipient != null && SET.contains(userEmail + ':' + part + ';' + validation + '>' + recipient)) {
                return userEmail + ':' + part + ';' + validation + '>' + recipient;
            } else if (userEmail != null && recipientDomain != null && SET.contains(userEmail + ':' + part + ';' + validation + '>' + recipientDomain)) {
                return userEmail + ':' + part + ';' + validation + '>' + recipientDomain;
            } else if (SET.contains(senderDomain + ';' + validation)) {
                return senderDomain + ';' + validation;
            } else if (recipient != null && SET.contains(senderDomain + ';' + validation + '>' + recipient)) {
                return senderDomain + ';' + validation + '>' + recipient;
            } else if (recipientDomain != null && SET.contains(senderDomain + ';' + validation + '>' + recipientDomain)) {
                return senderDomain + ';' + validation + '>' + recipientDomain;
            } else if (userEmail != null && SET.contains(userEmail + ':' + senderDomain + ';' + validation)) {
                return userEmail + ':' + senderDomain + ';' + validation;
            } else if (userEmail != null && recipient != null && SET.contains(userEmail + ':' + senderDomain + ';' + validation + '>' + recipient)) {
                return userEmail + ':' + senderDomain + ';' + validation + '>' + recipient;
            } else if (userEmail != null && recipientDomain != null && SET.contains(userEmail + ':' + senderDomain + ';' + validation + '>' + recipientDomain)) {
                return userEmail + ':' + senderDomain + ';' + validation + '>' + recipientDomain;
            } else if ((found = findHost(userEmail, sender, "." + senderDomain.substring(1), validation, recipient, recipientDomain, whoisSet, regexSet, false)) != null) {
                return found;
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
            } else if (recipient != null && SET.contains("@;" + validation +  ">" + recipient)) {
                return "@;" + validation +  ">" + recipient;
            } else if (recipientDomain != null && SET.contains("@;" + validation +  ">" + recipientDomain)) {
                return "@;" + validation +  ">" + recipientDomain;
            } else if (recipient != null && userEmail != null && SET.contains(userEmail + ":@;" + validation)) {
                return userEmail + ":@;" + validation;
            } else if (recipient != null && userEmail != null && SET.contains(userEmail + ":@;" + validation +  ">" + recipient)) {
                return userEmail + ":@;" + validation +  ">" + recipient;
            } else if (recipientDomain != null && userEmail != null && SET.contains(userEmail + ":@;" + validation +  ">" + recipientDomain)) {
                return userEmail + ":@;" + validation +  ">" + recipientDomain;
            } else {
                int index3 = senderDomain.length();
                while ((index3 = senderDomain.lastIndexOf('.', index3 - 1)) > index2) {
                    String subdomain = senderDomain.substring(0, index3 + 1);
                    if (SET.contains(subdomain + ';' + validation)) {
                        return subdomain + ';' + validation;
                    } else if (recipient != null && SET.contains(subdomain + ';' + validation + '>' + recipient)) {
                        return subdomain + ';' + validation + '>' + recipient;
                    } else if (recipientDomain != null && SET.contains(subdomain + ';' + validation + '>' + recipientDomain)) {
                        return subdomain + ';' + validation + '>' + recipientDomain;
                    } else if (userEmail != null && SET.contains(userEmail + ':' + subdomain + ';' + validation)) {
                        return userEmail + ':' + subdomain + ';' + validation;
                    } else if (userEmail != null && recipient != null && SET.contains(userEmail + ':' + subdomain + ';' + validation + '>' + recipient)) {
                        return userEmail + ':' + subdomain + ';' + validation + '>' + recipient;
                    } else if (userEmail != null && recipientDomain != null && SET.contains(userEmail + ':' + subdomain + ';' + validation + '>' + recipientDomain)) {
                        return userEmail + ':' + subdomain + ';' + validation + '>' + recipientDomain;
                    }
                }
                int index4 = sender.length();
                while ((index4 = sender.lastIndexOf('.', index4 - 1)) > index2) {
                    String subsender = sender.substring(0, index4 + 1);
                    if (SET.contains(subsender + ';' + validation)) {
                        return subsender + ';' + validation;
                    } else if (recipient != null && SET.contains(subsender + ';' + validation + '>' + recipient)) {
                        return subsender + ';' + validation + '>' + recipient;
                    } else if (recipientDomain != null && SET.contains(subsender + ';' + validation + '>' + recipientDomain)) {
                        return subsender + ';' + validation + '>' + recipientDomain;
                    } else if (userEmail != null && SET.contains(userEmail + ':' + subsender + ';' + validation)) {
                        return userEmail + ':' + subsender + ';' + validation;
                    } else if (userEmail != null && recipient != null && SET.contains(userEmail + ':' + subsender + ';' + validation + '>' + recipient)) {
                        return userEmail + ':' + subsender + ';' + validation + '>' + recipient;
                    } else if (userEmail != null && recipientDomain != null && SET.contains(userEmail + ':' + subsender + ';' + validation + '>' + recipientDomain)) {
                        return userEmail + ':' + subsender + ';' + validation + '>' + recipientDomain;
                    }
                }
                index4 = index2;
                while ((index4 = sender.lastIndexOf('.', index4 - 1)) > 0) {
                    String subsender = sender.substring(index4);
                    if (SET.contains(subsender + ';' + validation)) {
                        return subsender + ';' + validation;
                    } else if (recipient != null && SET.contains(subsender + ';' + validation + '>' + recipient)) {
                        return subsender + ';' + validation + '>' + recipient;
                    } else if (recipientDomain != null && SET.contains(subsender + ';' + validation + '>' + recipientDomain)) {
                        return subsender + ';' + validation + '>' + recipientDomain;
                    } else if (userEmail != null && SET.contains(userEmail + ':' + subsender + ';' + validation)) {
                        return userEmail + ':' + subsender + ';' + validation;
                    } else if (userEmail != null && recipient != null && SET.contains(userEmail + ':' + subsender + ';' + validation + '>' + recipient)) {
                        return userEmail + ':' + subsender + ';' + validation + '>' + recipient;
                    } else if (userEmail != null && recipientDomain != null && SET.contains(userEmail + ':' + subsender + ';' + validation + '>' + recipientDomain)) {
                        return userEmail + ':' + subsender + ';' + validation + '>' + recipientDomain;
                    }
                }
            }
            if (senderDomain.endsWith(".br")) {
                whoisSet.add(senderDomain);
            }
            regexSet.add(sender);
        }
        return null;
    }

    private static String findHost(String userEmail,
            String sender, String hostname, String qualifier,
            String recipient, String recipientDomain,
            TreeSet<String> whoisSet, TreeSet<String> regexSet, boolean full
    ) {
        do {
            int index = hostname.indexOf('.') + 1;
            hostname = hostname.substring(index);
            String token = '.' + hostname;
            if (SET.contains(token + ';' + qualifier)) {
                return token + ';' + qualifier;
            } else if (recipient != null && SET.contains(token + ';' + qualifier + '>' + recipient)) {
                return token + ';' + qualifier + '>' + recipient;
            } else if (recipientDomain != null && SET.contains(token + ';' + qualifier + '>' + recipientDomain)) {
                return token + ';' + qualifier + '>' + recipientDomain;
            } else if (userEmail != null && SET.contains(userEmail + ":@;" + hostname)) {
                return userEmail + ":@;" + hostname;
            } else if (userEmail != null && SET.contains(userEmail + ':' + token)) {
                return userEmail + ':' + token;
            } else if (userEmail != null && SET.contains(userEmail + ':' + token + ';' + qualifier)) {
                return userEmail + ':' + token + ';' + qualifier;
            } else if (userEmail != null && recipient != null && SET.contains(userEmail + ':' + token + ';' + qualifier + '>' + recipient)) {
                return userEmail + ':' + token + ';' + qualifier + '>' + recipient;
            } else if (userEmail != null && recipientDomain != null && SET.contains(userEmail + ':' + token + ';' + qualifier + '>' + recipientDomain)) {
                return userEmail + ':' + token + ';' + qualifier + '>' + recipientDomain;
            } else if (full && (token = findSender(userEmail, sender, hostname, recipient,
                    recipientDomain, whoisSet, regexSet)) != null) {
                return token;
            }
        } while (hostname.contains("."));
        return null;
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

    public static void store() {
        if (CHANGED) {
            try {
                Server.logTrace("storing white.set");
                long time = System.currentTimeMillis();
                File file = new File("./data/white.set");
                TreeSet<String> set = getAll();
                FileOutputStream outputStream = new FileOutputStream(file);
                try {
                    SerializationUtils.serialize(set, outputStream);
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
        File file = new File("./data/white.set");
        if (file.exists()) {
            try {
                Set<String> set;
                FileInputStream fileInputStream = new FileInputStream(file);
                try {
                    set = SerializationUtils.deserialize(fileInputStream);
                } finally {
                    fileInputStream.close();
                }
                // Processo temporário de transição.
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
                    if (client != null && client.startsWith("WHOIS/")) {
                        // Correção temporária do defeito no registro WHOIS.
                        while (client.startsWith("WHOIS/")) {
                            client = client.substring(6);
                        }
                        token = client + ':' + identifier;
                    }
                    if (identifier.startsWith("WHOIS/WHOIS/")) {
                        // Correção temporária do defeito no registro WHOIS.
                        token = null;
                    } else if (identifier.startsWith("WHOIS/")
                            && !identifier.contains("=")
                            && !identifier.contains("<")
                            && !identifier.contains(">")
                            ) {
                        // Correção temporária do defeito no registro WHOIS.
                        identifier = null;
                    } else if (Subnet.isValidCIDR(identifier)) {
                        identifier = "CIDR=" + Subnet.normalizeCIDR(identifier);
                    } else if (Owner.isOwnerID(identifier)) {
                        identifier = "WHOIS/ownerid=" + identifier;
                    } else {
                        identifier = normalizeTokenWhite(identifier);
                    }
                    if (identifier != null) {
                        try {
                            if (client == null) {
                                addExact(identifier);
                            } else if (Domain.isEmail(client)) {
                                addExact(client + ':' + identifier);
                            }
                        } catch (ProcessException ex) {
                            Server.logDebug("WHITE CIDR " + identifier + " " + ex.getErrorMessage());
                        }
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
