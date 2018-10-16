/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package bft.util;

import java.io.Serializable;
import org.apache.commons.codec.binary.Hex;

/**
 *
 * @author joao
 */
class OUIdentifier implements Serializable {
            
    // CertifiersIdentifier is the hash of certificates chain of trust
    // related to this organizational unit
    byte[] certifiersIdentifier;

    // OrganizationUnitIdentifier defines the organizational unit under the
    // MSP identified with MSPIdentifier
    String organizationalUnitIdentifier;

    OUIdentifier (byte[] certifiersIdentifier, String organizationalUnitIdentifier) {

        this.certifiersIdentifier = certifiersIdentifier;
        this.organizationalUnitIdentifier = organizationalUnitIdentifier;
    }

    @Override
    public int hashCode() {
        int hash = 7;
        hash = 31 * hash + this.certifiersIdentifier.hashCode();
        hash = 31 * hash + this.organizationalUnitIdentifier.hashCode();
        return hash;
    }

    @Override
    public boolean equals(Object o) {

        if (this == o) return true;
        if (o == null) return false;
        if (this.getClass() != o.getClass()) return false;
        OUIdentifier i = (OUIdentifier) o;
        return this.certifiersIdentifier.equals(i.certifiersIdentifier) && 
                this.organizationalUnitIdentifier.equals(i.organizationalUnitIdentifier);
    }

    @Override
    public String toString(){

        return "["+ Hex.encodeHexString(this.certifiersIdentifier) + ":" + this.organizationalUnitIdentifier+"]";
    }

}
