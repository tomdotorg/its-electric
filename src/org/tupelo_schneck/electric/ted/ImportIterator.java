/*
This file is part of
"it's electric": software for storing and viewing home energy monitoring data
Copyright (C) 2009--2012 Robert R. Tupelo-Schneck <schneck@gmail.com>
http://tupelo-schneck.org/its-electric

"it's electric" is free software: you can redistribute it and/or modify
it under the terms of the GNU Affero General Public License as
published by the Free Software Foundation, either version 3 of the
License, or (at your option) any later version.

"it's electric" is distributed in the hope that it will be useful,
but WITHOUT ANY WARRANTY; without even the implied warranty of
MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
GNU Affero General Public License for more details.

You should have received a copy of the GNU Affero General Public License
along with "it's electric", as legal/COPYING-agpl.txt.
If not, see <http://www.gnu.org/licenses/>.
*/

package org.tupelo_schneck.electric.ted;

import java.io.IOException;
import java.io.InputStream;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLConnection;
import java.util.Iterator;

import org.apache.commons.codec.binary.Base64;
import org.tupelo_schneck.electric.Options;
import org.tupelo_schneck.electric.Triple;
import org.tupelo_schneck.electric.Util;

import com.ibm.icu.util.GregorianCalendar;
import com.ibm.icu.util.TimeZone;

public class ImportIterator implements Iterator<Triple> {
    private final TimeZone timeZone;
    private final boolean useVoltage;
    private final byte mtu;
    private final InputStream urlStream;
    private final Base64 base64 = new Base64();
    private final GregorianCalendar cal;

    private byte[] line = new byte[25];
    private volatile boolean closed;
    private Triple pushback;
    private int inDSTOverlap; // 0 not, 1 first part, 2 second part
    private int previousTimestamp;

    public ImportIterator(final Options options, final byte mtu, final int count) throws IOException {
        this.timeZone = options.recordTimeZone;
        this.cal = new GregorianCalendar(this.timeZone); 
        this.mtu = mtu;
        this.useVoltage = options.voltage;
        URL url;
        try {
            url = new URL(options.gatewayURL+"/history/rawsecondhistory.raw?INDEX=1&MTU="+mtu+"&COUNT="+count);
        }
        catch (MalformedURLException e) {
            throw new RuntimeException(e);
        }
        URLConnection urlConnection = url.openConnection();
        urlConnection.setConnectTimeout(60000);
        urlConnection.setReadTimeout(60000);
        urlConnection.connect();
        urlStream = urlConnection.getInputStream();
        urlConnection.setReadTimeout(1000);
        getNextLine();
        
        // skip the first timestamp, in case we see only part of multiple values
        if(!closed) {
            Triple first = nextFromLine();
            Triple next = first;
            while(next!=null && next.timestamp == first.timestamp) {
                next = nextFromLine();
            }
            pushback = next;
            
            if(Util.inDSTOverlap(timeZone, first.timestamp)) {
                int now = (int)(System.currentTimeMillis()/1000);
                if(now < first.timestamp - 1800) inDSTOverlap = 1;
                else inDSTOverlap = 2;
            }
            previousTimestamp = first.timestamp;
        }
    }

    public void close() {
        closed = true;
        if(urlStream!=null) try { urlStream.close(); } catch (Exception e) { e.printStackTrace(); }
    }

    private void getNextLine() {
        try {
            int n = 0;
            while(n < 25) {
                int r = urlStream.read(line,n,25-n);
                if(r <= 0) {
                    close();
                    return;
                }
                n += r;
            }
            if(line[24]!='\n') close();
        }
        catch(IOException e) {
            close();
        }
    }
    
    @Override
    public void remove() {
        throw new UnsupportedOperationException();
    }
    
    @Override
    public boolean hasNext() {
        return pushback!=null || !closed;
    }

    /** The opposite of TimeSeriesDatabase.intOfBytes */
    private static int intOfBytes(byte[] buf, int offset) {
        int res = 0;    
        res |= ((buf[offset+3] & 0xFF) << 24); 
        res |= ((buf[offset+2] & 0xFF) << 16); 
        res |= ((buf[offset+1] & 0xFF) << 8); 
        res |= ((buf[offset+0] & 0xFF));
        return res;
    }

    private static int unsignedShortOfBytes(byte[] buf, int offset) {
        int res = 0;    
        res |= ((buf[offset+1] & 0xFF) << 8); 
        res |= ((buf[offset+0] & 0xFF));
        return res;
    }

    private Triple nextFromLine() {
        if(closed) return null;
        byte[] decoded = base64.decode(line);
        if(decoded==null || decoded.length<16) return null;
        if((0x00FF & decoded[0]) < 9) {
            // TED5000 uses sentinel value 2005-05-05 05:05:05.  
            // Here we skip anything before 2009.
            getNextLine();
            return next();
        }
        cal.set(2000+(0x00FF & decoded[0]), decoded[1]-1, decoded[2], decoded[3], decoded[4], decoded[5]);
        Integer power = Integer.valueOf(intOfBytes(decoded,6));
        Integer voltage = useVoltage ? Integer.valueOf(unsignedShortOfBytes(decoded,14)) : null;
        int timestamp = (int)(cal.getTimeInMillis() / 1000);
        if(inDSTOverlap==2 && timestamp > previousTimestamp) inDSTOverlap = 1;
        if(inDSTOverlap==1) {
            if(Util.inDSTOverlap(timeZone, timestamp)) timestamp -= 3600;
            else inDSTOverlap = 0;
        }
        previousTimestamp = timestamp;
        Triple res = new Triple(timestamp,mtu,power,voltage,null);
        getNextLine();
        return res;
    }

    private Triple privateNext() {
        if(pushback!=null) {
            Triple res = pushback;
            pushback = null;
            return res;
        }
        if(!closed) return nextFromLine();
        return null;
    }
    
    @Override
    public Triple next() {
        Triple first = privateNext();
        if(first==null) return null;
        int timestamp = first.timestamp;
        int power = first.power.intValue();
        int voltage = useVoltage ? first.voltage.intValue() : 0;
        int count = 1;
        while(true) {
            Triple next = privateNext();
            if(next==null || next.timestamp != timestamp) {
                pushback = next;
                // skip the last timestamp, in case we see only part of multiple values
                if(next==null) {
                    close();
                    return null;
                }
                break;
            }
            else {
                count++;
                power += next.power.intValue();
                voltage += useVoltage ? next.voltage.intValue() : 0;
            }
        }
        if(count==1) return first;
        else return new Triple(timestamp, first.mtu, Integer.valueOf(power/count), useVoltage ? Integer.valueOf(voltage/count) : null, null);
    }
}