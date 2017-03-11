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

package net.spfbl.core;

import com.sun.mail.smtp.SMTPAddressFailedException;

/**
 * Exceção de processamento.
 * @author Leandro Carlos Rodrigues <leandro@spfbl.net>
 */
@SuppressWarnings("serial")
public class ProcessException extends Exception {
    
    public ProcessException(String message) {
        super(message);
    }
    
    public ProcessException(String message, Throwable cause) {
        super(message, cause);
    }
    
    public boolean isErrorMessage(String message) {
        if (message == null) {
            return false;
        } else {
            return message.equals(getErrorMessage());
        }
    }

    public String getErrorMessage() {
        String message = getMessage();
        if (message.startsWith("ERROR: ")) {
            return message.substring(7);
        } else {
            return message;
        }
    }

    public Throwable getCause(Class causeClass) {
        Throwable cause = this;
        while ((cause = cause.getCause()) != null) {
            if (cause.getClass().equals(causeClass)) {
                return cause;
            }
        }
        return null;
    }
}
