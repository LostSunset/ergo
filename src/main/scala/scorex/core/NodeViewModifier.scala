package scorex.core

import org.ergoplatform.modifiers.NetworkObjectTypeId
import org.ergoplatform.modifiers.mempool.ErgoTransaction
import scorex.core.serialization.BytesSerializable
import scorex.core.utils.ScorexEncoding

sealed trait NodeViewModifier extends BytesSerializable with ScorexEncoding {self =>

  val modifierTypeId: NetworkObjectTypeId.Value

  //todo: check statically or dynamically output size
  def id: scorex.util.ModifierId

  override def equals(obj: scala.Any): Boolean = obj match {
    case that: NodeViewModifier => (that.id == id) && (that.modifierTypeId == modifierTypeId)
    case _ => false
  }

  /**
    * @return readable representation of `id`, as `id` is a hex-encoded string now, just identity functions is used
    */
  def encodedId: String = id

}

trait EphemerealNodeViewModifier extends NodeViewModifier

/**
  * It is supposed that all the modifiers (offchain transactions, blocks, blockheaders etc)
  * have identifiers of the some length fixed with the ModifierIdSize constant
  */
object NodeViewModifier {
  val ModifierIdSize: Int = 32 // in bytes
}

/**
  * Persistent node view modifier is a part of replicated log of state transformations.
  * The log should be deterministic and ordered to get a deterministic state after its
  * application to the genesis state and thus every modifier should have parent - modifier,
  * which application should precede this modifier application
  */
trait PersistentNodeViewModifier extends NodeViewModifier {
  /**
    * Id modifier, which should be applied to the node view before this modifier
    */
  def parentId: scorex.util.ModifierId
}


trait TransactionsCarryingPersistentNodeViewModifier
  extends PersistentNodeViewModifier {

  def transactions: Seq[ErgoTransaction]
}
