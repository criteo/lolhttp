git checkout master
sbt unidoc
git checkout gh-pages
cp -rf target/scala-2.12/unidoc api
git commit -a -m "New website"
git push