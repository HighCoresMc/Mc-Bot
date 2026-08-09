import xml.etree.ElementTree as ET

tree = ET.parse('pom.xml')
root = tree.getroot()
ns = {'mvn': 'http://maven.apache.org/POM/4.0.0'}
ET.register_namespace('', 'http://maven.apache.org/POM/4.0.0')

dependencies = root.find('mvn:dependencies', ns)
if dependencies is not None:
    # check if icu4j exists
    exists = False
    for dep in dependencies.findall('mvn:dependency', ns):
        art = dep.find('mvn:artifactId', ns)
        if art is not None and art.text == 'icu4j':
            exists = True
            break
    if not exists:
        new_dep = ET.Element('{http://maven.apache.org/POM/4.0.0}dependency')
        g = ET.SubElement(new_dep, '{http://maven.apache.org/POM/4.0.0}groupId')
        g.text = 'com.ibm.icu'
        a = ET.SubElement(new_dep, '{http://maven.apache.org/POM/4.0.0}artifactId')
        a.text = 'icu4j'
        v = ET.SubElement(new_dep, '{http://maven.apache.org/POM/4.0.0}version')
        v.text = '73.2'
        dependencies.append(new_dep)
        tree.write('pom.xml', encoding='utf-8', xml_declaration=True)
        print("Added icu4j to pom.xml")
    else:
        print("icu4j already in pom.xml")
