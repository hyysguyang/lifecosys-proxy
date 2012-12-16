//val Name = """(Mr|Mrs|Ms)\. ([A-Z][a-z]+) ([A-Z][a-z]+)""".r
//val smith = "Mr. John Smith"
//val Name(title, first, last) = smith
//
//
//val hr="""(.*//|^)([a-zA-Z\d.]+)\:*(\d*).*""".r
//val hr(test,host,port)="http://127.0.0.1:8990/"
//
//
//val hr(test1,host1,port1)="https://127.0.0.1:8990/"
//
//
//val hr(test2,host2,port2)="127.0.0.1:8990/"
//
//
//val hr(test3,host3,port3)="127.0.0.1:8990"
//
//
//val hr(test4,host4,port4)="127.0.0.1"
//
//
//val hr(test5,host5,port5)="127.0.0.1/test/sss/tyty/8989"
//
//
//val hr(test6,host6,port6)="127.0.0.1/test/sss/tyty/89:89"
//
//
//val hr(test7,host7,port7)="127.0.0.1:8990/"
//
//
//val hr(test8,host8,port8)="127.0.0.1:8990/"
//

object test{

  class Upper {
    val name = {
      println("Upper")
      "Upper Name"
    }
  }
  class Lower extends Upper {
    override val name = {
      println("Lower")
      "Lower Name"

    }
  }




}