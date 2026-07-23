package com.flowagent.common.util;

import io.micrometer.common.util.StringUtils;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;

import java.net.DatagramSocket;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.Optional;

@Slf4j
public class IpUtil {
    private static final String UNKNOWN = "unKnown";
    public static final String DEFAULT_IP = "127.0.0.1";
    private static String localIp;

    private static List<Inet4Address> getLocalIp4AddressFromNetworkInterface() throws SocketException {
        List<Inet4Address> addresses = new ArrayList<>(1);
        Enumeration<NetworkInterface> networkInterfaces = NetworkInterface.getNetworkInterfaces();
        if (!networkInterfaces.hasMoreElements()) {
            return addresses;
        }
        while (networkInterfaces.hasMoreElements()) {
            NetworkInterface networkInterface = networkInterfaces.nextElement();
            if (!isValidInterface(networkInterface)) {
                continue;
            }
            Enumeration<InetAddress> inetAddresses = networkInterface.getInetAddresses();
            while (inetAddresses.hasMoreElements()) {
                InetAddress inetAddress = inetAddresses.nextElement();
                if (isValidAddress(inetAddress)) {
                    addresses.add((Inet4Address) inetAddress);
                }
            }
        }
        return addresses;
    }

    private static boolean isValidInterface(NetworkInterface ni) throws SocketException {
        return !ni.isLoopback() && !ni.isPointToPoint() && ni.isUp() && !ni.isVirtual()
                && (ni.getName().startsWith("eth") || ni.getName().startsWith("ens"));
    }

    private static boolean isValidAddress(InetAddress address) {
        return address instanceof Inet4Address && address.isSiteLocalAddress() && !address.isLoopbackAddress();
    }

    private static Optional<Inet4Address> getIpBySocket() throws SocketException {
        try (DatagramSocket socket = new DatagramSocket()) {
            socket.connect(InetAddress.getByName("8.8.8.8"), 10002);
            if (socket.getLocalAddress() instanceof Inet4Address inet4Address) {
                return Optional.of(inet4Address);
            }
        } catch (UnknownHostException e) {
            throw new RuntimeException(e);
        }
        return Optional.empty();
    }

    public static String getLocalIp4Address() throws SocketException {
        if (localIp != null) {
            return localIp;
        }
        final List<Inet4Address> inet4Addresses = getLocalIp4AddressFromNetworkInterface();
        if (inet4Addresses.size() != 1) {
            final Optional<Inet4Address> ipBySocketOpt = getIpBySocket();
            localIp = ipBySocketOpt.map(Inet4Address::getHostAddress)
                    .orElseGet(() -> inet4Addresses.isEmpty() ? DEFAULT_IP : inet4Addresses.get(0).getHostAddress());
            return localIp;
        }
        localIp = inet4Addresses.get(0).getHostAddress();
        return localIp;
    }

    public static String getClientIp(HttpServletRequest request) {
        try {
            String xIp = request.getHeader("X-Real-IP");
            String xFor = request.getHeader("X-Forwarded-For");
            if (StringUtils.isNotEmpty(xFor) && !UNKNOWN.equalsIgnoreCase(xFor)) {
                int index = xFor.indexOf(",");
                return index != -1 ? xFor.substring(0, index) : xFor;
            }
            xFor = xIp;
            if (StringUtils.isNotEmpty(xFor) && !UNKNOWN.equalsIgnoreCase(xFor)) {
                return xFor;
            }
            if (StringUtils.isBlank(xFor) || UNKNOWN.equalsIgnoreCase(xFor)) {
                xFor = request.getHeader("Proxy-Client-IP");
            }
            if (StringUtils.isBlank(xFor) || UNKNOWN.equalsIgnoreCase(xFor)) {
                xFor = request.getHeader("WL-Proxy-Client-IP");
            }
            if (StringUtils.isBlank(xFor) || UNKNOWN.equalsIgnoreCase(xFor)) {
                xFor = request.getHeader("HTTP_CLIENT_IP");
            }
            if (StringUtils.isBlank(xFor) || UNKNOWN.equalsIgnoreCase(xFor)) {
                xFor = request.getHeader("HTTP_X_FORWARDED_FOR");
            }
            if (StringUtils.isBlank(xFor) || UNKNOWN.equalsIgnoreCase(xFor)) {
                xFor = request.getRemoteAddr();
            }
            if ("localhost".equalsIgnoreCase(xFor) || "127.0.0.1".equalsIgnoreCase(xFor)
                    || "0:0:0:0:0:0:0:1".equalsIgnoreCase(xFor)) {
                return getLocalIp4Address();
            }
            return xFor;
        } catch (Exception e) {
            log.error("get remote ip error!", e);
            return "x.0.0.1";
        }
    }
}
