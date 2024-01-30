package org.ergoplatform.utils

import java.math.BigInteger

import org.bouncycastle.util.BigIntegers
import org.ergoplatform.mining.AutolykosSolution
import org.ergoplatform.mining.emission.EmissionRules
import org.ergoplatform._
import org.ergoplatform.modifiers.history.header.Header
import org.ergoplatform.modifiers.mempool.{ErgoTransaction, UnsignedErgoTransaction}
import org.ergoplatform.nodeView.ErgoContext
import org.ergoplatform.nodeView.state.{ErgoStateContext, VotingData}
import org.ergoplatform.settings.{ErgoSettings, ErgoValidationSettings, LaunchParameters}
import org.ergoplatform.wallet.boxes.ErgoBoxSerializer
import org.ergoplatform.wallet.interpreter.{ErgoInterpreter, ErgoProvingInterpreter}
import org.ergoplatform.wallet.protocol.context.{ErgoLikeParameters, InputContext, TransactionContext}
import scorex.crypto.authds.ADDigest
import scorex.crypto.hash.{Blake2b256, Digest32}
import sigmastate.interpreter.{ContextExtension, HintsBag}
import sigmastate.eval.{IRContext, RuntimeIRContext}
import sigmastate.lang.Terms._
import sigmastate.interpreter._
import scorex.util.bytesToId
import scorex.util.encode.Base16
import sigmastate.NodePosition
import sigmastate.basics.DLogProtocol.{DLogInteractiveProver, DLogProverInput, FirstDLogProverMessage, ProveDlog}
import sigmastate.serialization.{GroupElementSerializer, SigmaSerializer}


class OldProvingInterpreter(seed: String,
                            params: ErgoLikeParameters,
                            hintsBag: HintsBag)
                           (implicit IR: IRContext) extends
  ErgoProvingInterpreter(IndexedSeq(), params: ErgoLikeParameters, None){

  val numOfSecrets = 1
  def secretsFromSeed(seedStr: String): IndexedSeq[BigInteger] = {
    (1 to numOfSecrets).map { i =>
      BigIntegers.fromUnsignedByteArray(Blake2b256.hash(i + seedStr))
    }
  }

  override val secrets = secretsFromSeed(seed).map(DLogProverInput.apply)
}


// Script for 2-out-of 3 spending of foundation box
object FoundationBoxSigner extends App {
  implicit lazy val ircontext: IRContext = new RuntimeIRContext

  type ACTION = Int
  val generateCommitment: ACTION = 0
  val preSign: ACTION = 1
  val sign: ACTION = 2

  val height = 1165000

  //data which should be MANUALLY changed in order to interact with the program
  val seed = "..."
  val action: ACTION = preSign

  // hints provided by a cosigner
  val commitmentStringOpt: Option[String] = Some("034f0a3706d34fb17525d16eb6e44b4e955341ccd12430fa1d971cfb47295683aa")
  val ownRandomnessStringOpt: Option[String] = None
  val partialSignatureStringOpt: Option[String] = None

  //signer is generating commitment and final valid sig
  val signerIndex = 1 //0, 1, 2

  val cmtOpt = commitmentStringOpt.map(Base16.decode).map(_.get).map(SigmaSerializer.startReader(_))
                  .map(GroupElementSerializer.parse).map(FirstDLogProverMessage.apply)
  val ownRandomnessOpt = ownRandomnessStringOpt.map(new BigInteger(_))
  val partialSignatureOpt = partialSignatureStringOpt.map(Base16.decode).map(_.get)

  val inactiveIndexes = (0 to 2).filter(i => i != signerIndex)

  val prover = new OldProvingInterpreter(seed, LaunchParameters, HintsBag.empty)
  implicit val verifier = new ErgoInterpreter(LaunchParameters)

  val pubKeys = IndexedSeq(
    "039bb5fe52359a64c99a60fd944fc5e388cbdc4d37ff091cc841c3ee79060b8647",
    "031fb52cf6e805f80d97cde289f4f757d49accf0c83fb864b27d2cf982c37f9a8b",
    "0352ac2a471339b0d23b3d2c5ce0db0e81c969f77891b9edf0bda7fd39a78184e7"
  ).map(Base16.decode).map(_.get).map(SigmaSerializer.startReader(_))
    .map(GroupElementSerializer.parse)
      .map(ProveDlog.apply)

  //forming a context. Only height matters for the foundation box.
  implicit val settings = ErgoSettings.read()
  val parameters = LaunchParameters
  val genesisStateDigest = ADDigest @@ Array.fill(33)(0: Byte)
  val sol = AutolykosSolution(
    CryptoConstants.dlogGroup.generator,
    CryptoConstants.dlogGroup.generator,
    Array.fill(8)(0:Byte),
    0)
  val h = Header(1.toByte, bytesToId(Array.fill(32)(0: Byte)), Digest32 @@ Array.fill(32)(0: Byte),
    ADDigest @@ Array.fill(33)(0: Byte), Digest32 @@ Array.fill(32)(0: Byte), 0L, 0L, height,
    Digest32 @@ Array.fill(32)(0: Byte), sol, Array.fill(3)(0: Byte))
  val stateContext = new ErgoStateContext(Seq(h), None, genesisStateDigest, parameters, ErgoValidationSettings.initial, VotingData.empty)


  //box and message to sign
  //copy "new out bytes" here
  val gfBytes = Base16.decode("8088fff8d29a4d100e040004c094400580809cde91e7b0010580acc7f03704be944004808948058080c7b7e4992c0580b4c4c32104fe884804c0fd4f0580bcc1960b04befd4f05000400ea03d192c1b2a5730000958fa373019a73029c73037e997304a305958fa373059a73069c73077e997308a305958fa373099c730a7e99730ba305730cd193c2a7c2b2a5730d00d5040800f8fd4600010e6f98040483030808cd039bb5fe52359a64c99a60fd944fc5e388cbdc4d37ff091cc841c3ee79060b864708cd031fb52cf6e805f80d97cde289f4f757d49accf0c83fb864b27d2cf982c37f9a8b08cd0352ac2a471339b0d23b3d2c5ce0db0e81c969f77891b9edf0bda7fd39a78184e727f752fb51b179e9d2362bfe86ed567e7957f81b2401e0a0d6521d7985547c9600").get
  val gfBox = ErgoBoxSerializer.parseBytes(gfBytes)

  println("Spending: " + Base16.encode(gfBox.id))

  val boxToSpend: ErgoBox = gfBox
  val input = new UnsignedInput(boxToSpend.id, ContextExtension.empty)

  //outputs
  val fee = EmissionRules.CoinsInOneErgo / 10 //0.1 Erg
  val feeBox = new ErgoBoxCandidate(fee, ErgoScriptPredef.feeProposition(), height)

  //waves gateway address for Ergo Waves account
  val enc = new ErgoAddressEncoder(ErgoAddressEncoder.MainnetNetworkPrefix)

  val addr = enc.fromString("9g1ReLmsbGevkTjfPGMdRMoFRdrAVskZVmSBpaEdVW2DfG4HDWm").get// "2BggBDgr9n9geTKjCJBCEWMReb2i7wcocw7fjVd3QyM7qFMtmVHyoFr78kChAxGekJxUTZru2aMjyZKcVoPfHX5d12RqNrnEAgzGqUCoJ2zkCiiUURror6NJ6HYyPxxUf5qj5FdQXqJ2zFv1a8U9Lieib59S8mxpE2oLAPt7P5cyngAa5sVEHHKeJrKkpp2yfQH3kshujjPCCc6qv2StJkmbzDTqAcxTpjcB9voMhi1tCybU4ikxSJEoAKmr5mppSfVAB5zSpREYstCVU").get

  val withdrawalAmount = 5785 * EmissionRules.CoinsInOneErgo
  val withdrawalOutputs = IndexedSeq(new ErgoBoxCandidate(withdrawalAmount, addr.script, height))

  val foundationOutput = new ErgoBoxCandidate(gfBox.value - withdrawalAmount - fee, gfBox.ergoTree, height,
                                              gfBox.additionalTokens, gfBox.additionalRegisters)

  val outputCandidates = IndexedSeq(foundationOutput) ++ withdrawalOutputs ++ IndexedSeq(feeBox)
  val undersignedTx = UnsignedErgoTransaction(IndexedSeq(input), outputCandidates)
  val transactionContext = TransactionContext(IndexedSeq(boxToSpend), IndexedSeq(), undersignedTx)
  val inputContext = InputContext(0, ContextExtension.empty)
  val msgToSign = undersignedTx.messageToSign

  val context = new ErgoContext(stateContext, transactionContext, inputContext, parameters.maxBlockCost, 0)
  val prop = gfBox.proposition.asSigmaProp

  // doing a requested action
  action match {
    case i: Int if i == generateCommitment =>
      val (r, c) = DLogInteractiveProver.firstMessage()
      println("randomness(store it in secret!): " + r)
      println("commitment: " + Base16.encode(GroupElementSerializer.toBytes(c.ecData)))

    case i: Int if i == preSign =>
      val signerPubKey = pubKeys(signerIndex)
      val hint = RealCommitment(signerPubKey, cmtOpt.get, NodePosition(Seq(0, signerIndex)))
      val bag = HintsBag(IndexedSeq(hint))
      val partialProof = prover.prove(prop, context, msgToSign, bag).get
      println("Commitment: " + commitmentStringOpt)
      println("Partial proof: " + Base16.encode(partialProof.proof))

    case i: Int if i == sign =>
      val ownRandomness = ownRandomnessOpt.get
      val partialSig = partialSignatureOpt.get

      val simulatedPubkeys = Seq(0).map(pubKeys.apply)
      val realPubkeys = Seq(1, 2).map(pubKeys.apply)
      val bag = prover.bagForMultisig(context, prop, partialSig, realPubkeys, simulatedPubkeys)
        .addHint(OwnCommitment(pubKeys(signerIndex), ownRandomness, cmtOpt.get, NodePosition(Seq(0, signerIndex))))

      val proof = prover.prove(prop, context, msgToSign, bag).get

      val check = verifier.verify(prop, context, proof, msgToSign)
      println("proof is correct: " + check)

      val input = Input(boxToSpend.id, proof)
      val tx = ErgoTransaction(IndexedSeq(input), undersignedTx.outputCandidates)

      val out = tx.outputs.head
      val outId = Base16.encode(out.id)

      println("tx is valid: " +tx.validateStateful(IndexedSeq(boxToSpend), IndexedSeq(), stateContext, 0).result)

      println("New foundation box id: " + outId)

      println(ErgoTransaction.transactionEncoder.apply(tx))

      println("new out bytes: " + Base16.encode(ErgoBoxSerializer.toBytes(tx.outputs.head)))
  }

}
