package pkg

func foo() {
  // error: multiset literals cannot contain keys
  //:: ExpectedOutput(type_error)
  ghost m := mset[int] { 0 : 12 }
}
