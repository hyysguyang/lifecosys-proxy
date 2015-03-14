package com.lifecosys.toolkit.dns
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.ArrayList
import com.typesafe.scalalogging.Logger
import org.xbill.DNS.RRset
import org.xbill.DNS.Name
import org.xbill.DNS.Resolver
import org.xbill.DNS.Record
import org.xbill.DNS.Type
import org.xbill.DNS.DClass
import org.xbill.DNS.Message
import org.xbill.DNS.Section
import org.xbill.DNS.CNAMERecord
import org.xbill.DNS.ARecord
import org.xbill.DNS.RRSIGRecord
import org.xbill.DNS.DNSKEYRecord
import org.xbill.DNS.Options
import org.xbill.DNS.DNSSEC
import org.xbill.DNS.ExtendedResolver
import org.xbill.DNS.ExtendedFlags
import org.slf4j.LoggerFactory
import scala.util.Try

/**
 * Copy from org.littleshoot.dnssec4j.DnsSec, see https://adamfisk@github.com/adamfisk/DNSSEC4J.git
 *
 * DNSSEC resolver and validator.
 *
 * @author Young Gu
 * @version 1.0 8/18/13 12:18 AM
 */
object DnsSec {
  protected lazy val logger = Logger(LoggerFactory getLogger getClass.getName)

  {
    System.setProperty("sun.net.spi.nameservice.nameservers", "8.8.8.8,8.8.4.4");
    //System.setProperty("sun.net.spi.nameservice.nameservers", "75.75.75.75 ,75.75.75.76");
    // These are from https://www.dns-oarc.net/oarc/services/odvr
    //System.setProperty("sun.net.spi.nameservice.nameservers", "149.20.64.20,149.20.64.21");
  }

  /**
   * Creates a new InetSocketAddress, verifying the host with DNSSEC if
   * configured to do so.
   *
   * @param host The host.
   * @param port The port.
   * @param useDnsSec Whether or not to use DNSSEC.
   * @return The endpoint.
   * @throws UnknownHostException If the host cannot be resolved.
   */
  def newInetSocketAddress(host: String, port: Int, useDnsSec: Boolean): InetSocketAddress = {
    return new InetSocketAddress(newVerifiedInetAddress(host, useDnsSec), port)
  }

  /**
   * Creates a new InetSocket, verifying the host with DNSSEC if
   * configured to do so.
   *
   * @param host The host.
   * @param useDnsSec Whether or not to use DNSSEC.
   * @return The { @link InetAddress}.
   * @throws UnknownHostException If the host cannot be resolved.
   */
  def newVerifiedInetAddress(host: String, useDnsSec: Boolean): InetAddress = {
    if (useDnsSec) {
      Try(getByName(host)).getOrElse(None).getOrElse(InetAddress.getByName(host))
    } else
      InetAddress.getByName(host)
  }

  /**
   * If the specified {@link InetSocketAddress} has not already resolved to
   * an IP address, this verifies the host name and returns a new, verified
   * and resolved {@link InetSocketAddress}.
   *
   * @param unresolved An unresolved { @link InetSocketAddress}.
   * @return The resolved and verified { @link InetSocketAddress}.
   * @throws DNSSECException If there's a problem with the DNS signature.
   * @throws IOException If there's a problem with the nameservers.
   */
  def verify(unresolved: InetSocketAddress): InetSocketAddress = {
    if (!unresolved.isUnresolved) {
      return unresolved
    }

    val verified: InetAddress = Try(getByName(unresolved.getHostName)).getOrElse(None).getOrElse(InetAddress.getByName(unresolved.getHostName))
    return new InetSocketAddress(verified, unresolved.getPort)
  }

  /**
   * Access the specified URL and verifies the signatures of DNSSEC responses
   * if they exist, returning the resolved IP address.
   *
   * @param name The name of the site.
   * @return The IP address for the specified domain, verified if possible.
   * @throws IOException If there's an IO error accessing the nameservers or
   *                     sending or receiving messages with them.
   * @throws DNSSECException If there's a DNS error verifying the signatures
   *                         for any domain.
   */
  def getByName(name: String): Option[InetAddress] = {
    val full = Name.concatenate(Name.fromString(name), Name.root)
    logger.debug(s"Verifying record:$full ")
    val resolver = newResolver
    val question = Record.newRecord(full, Type.A, DClass.IN)
    val query = Message.newQuery(question)
    val response: Message = resolver.send(query)
    logger.debug(s"Response: $response")
    val answer: Array[RRset] = response.getSectionRRsets(Section.ANSWER)
    val addresses = new ArrayList[InetAddress]
    for (set ← answer) {
      logger.debug("\n;; RRset to chase:")
      // First check for a CNAME and target.
      var rrIter = set.rrs

      var hasCname: Boolean = false

      var cNameTarget: Name = null

      while (rrIter.hasNext) {
        val rec: Record = rrIter.next.asInstanceOf[Record]
        val `type`: Int = rec.getType
        if (`type` == Type.CNAME) {
          val cname: CNAMERecord = rec.asInstanceOf[CNAMERecord]
          hasCname = true
          cNameTarget = cname.getTarget
        }
      }
      rrIter = set.rrs
      while (rrIter.hasNext) {
        val rec: Record = rrIter.next.asInstanceOf[Record]
        val `type`: Int = rec.getType
        if (`type` == Type.A) {
          val arec: ARecord = rec.asInstanceOf[ARecord]
          if (hasCname) {
            if (rec.getName == cNameTarget) {
              addresses.add(arec.getAddress)
            }
          } else {
            addresses.add(arec.getAddress)
          }
        }
      }
      val sigIter = set.sigs
      while (sigIter.hasNext) {
        val rec: RRSIGRecord = sigIter.next.asInstanceOf[RRSIGRecord]
        logger.debug("\n;; RRSIG of the RRset to chase:")
        verifyZone(set, rec)
      }
    }
    import scala.collection.JavaConversions._
    addresses.toList.headOption
  }

  private def verifyZone(set: RRset, record: RRSIGRecord) {
    logger.debug("\nLaunch a query to find a RRset of type DNSKEY for zone: " + record.getSigner)
    val signer: Name = record.getSigner
    val tag: Int = record.getFootprint
    logger.debug("Looking for tag: " + tag)
    var keyVerified: Boolean = false
    var keyRec: DNSKEYRecord = null
    try {
      Options.set("multiline")
      val res: Resolver = newResolver
      val question: Record = Record.newRecord(signer, Type.DNSKEY, DClass.IN)
      val query: Message = Message.newQuery(question)
      val response: Message = res.send(query)
      logger.debug("Sent query...")
      val answer: Array[RRset] = response.getSectionRRsets(Section.ANSWER)
      for (answerSet ← answer) {
        logger.debug("\n;; DNSKEYset that signs the RRset to chase:")
        val rrIter = answerSet.rrs
        while (rrIter.hasNext) {
          val rec: Record = rrIter.next.asInstanceOf[Record]
          if (rec.isInstanceOf[DNSKEYRecord]) {
            val dnskKeyRec: DNSKEYRecord = rec.asInstanceOf[DNSKEYRecord]
            if (dnskKeyRec.getFootprint == tag) {
              logger.debug("\n\nFound matching DNSKEY for tag!! " + tag + "\n\n")
              keyRec = dnskKeyRec
            }
          }
        }
        logger.debug("\n;; RRSIG of the DNSKEYset that signs the RRset to chase:")
        val sigIter = answerSet.sigs
        while (sigIter.hasNext) {
          val rec: RRSIGRecord = sigIter.next.asInstanceOf[RRSIGRecord]
          if (rec.getFootprint == tag) {
            DNSSEC.verify(answerSet, rec, keyRec)
            keyVerified = true
          }
        }
        if (!keyVerified) {
          logger.info("DNSKEY not verified")
        } else {
          logger.info("DNSKEY verified!!")
        }
        keyVerified = false
      }
      if (keyRec == null) {
        throw new RuntimeException("Did not find DNSKEY record matching tag: " + tag)
      }
    } finally {
      Options.unset("multiline")
    }
    DNSSEC.verify(set, record, keyRec)
    verifyDsRecordForSignerOf(record)
  }

  private def newResolver: Resolver = {
    val res = new ExtendedResolver
    res.setEDNS(0, 0, ExtendedFlags.DO, null)
    res.setIgnoreTruncation(false)
    res.setTimeout(15)
    return res
  }

  private def verifyDsRecordForSignerOf(rec: RRSIGRecord) {
    val signer: Name = rec.getSigner
    logger.debug("\nLaunch a query to find a RRset of type DS for zone: " + signer)
    val res: Resolver = newResolver
    val question: Record = Record.newRecord(signer, Type.DS, DClass.IN)
    val query: Message = Message.newQuery(question)
    val response: Message = res.send(query)
    logger.debug("Sent query and got response: " + response)
    val answer: Array[RRset] = response.getSectionRRsets(Section.ANSWER)
    for (set ← answer) {
      val rrIter = set.rrs
      logger.debug("\n;; DSset of the DNSKEYset")
      while (rrIter.hasNext) {
      }
      val sigIter = set.sigs
      logger.debug("\n;; RRSIG of the DSset of the DNSKEYset")
      while (sigIter.hasNext) {
        val sigRec: Record = sigIter.next.asInstanceOf[Record]
        if (sigIter.hasNext) {
          throw new IOException("We don't handle more than one RRSIGRecord for DS responses!!")
        }
        if (sigRec.isInstanceOf[RRSIGRecord]) {
          val rr: RRSIGRecord = sigRec.asInstanceOf[RRSIGRecord]
          logger.debug(";; Now, we want to validate the DS :  recursive call")
          verifyZone(set, rr)
        }
      }
    }
    logger.debug(";; Out of recursive call")
  }
}

