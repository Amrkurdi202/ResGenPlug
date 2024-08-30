# ResGenPlug

This is the maven plugin that generates resources for Quentity framework.

## Concept
It reads the java code using Java parser then generates the resource by looking into your project .properties files (one for each language),
each file will be updated with the missing resources of the current project.

## Output
It generates resources for:
* The entity classes.
* The member fields of the entity that got Res class as ancestor.
* The entity packages recursively.

## Example
We have defined entities in myview package:

<br>myview/
<br>├── Human
<br>└── Item

### Human class
```java
@jakarta.persistence.Entity
@Component
public class Human extends Entity<Human> {

    public FldString name, age, address;

    public void define() {
        name.setMaxLength(5).setMask("^[\\s\\w]+$");
        age.setMaxLength(5).setMask("^[\\s\\w]+$");
        address.setMaxLength(5).setMask("^[\\s\\w]+$");
    }
}
```

### Item class
```java
@jakarta.persistence.Entity
@Component
public class Item extends Entity<Item> {

  public FldString name, price;

  public void define() {
    name.setMaxLength(5).setMask("^[\\s\\w]+$");
    price.setMaxLength(5).setMask("^[\\s\\w]+$");
  }
}
```

The generated resources are:

* com.quentity.views.myview=
* com.quentity.views.myview.Human=
* com.quentity.views.myview.Human.address=
* com.quentity.views.myview.Human.age=
* com.quentity.views.myview.Human.name=
* com.quentity.views.myview.Item=
* com.quentity.views.myview.Item.name=
* com.quentity.views.myview.Item.price=