# data-ape
A containerized database browser.

For now this only works with MongoDB and one database defined in the properties:

1. Build GUI:
   - cd da-frontend
   - ng build --configuration production --progress --verbose
2. Build & package server:
   - cd da-server
   - mvn package
3. This should yield a docker image in da-server/target/. Import it.
4. Create & prepare a docker volume (and assuming standard location):
   - docker volume create data-ape
   -  mkdir /var/lib/docker/volumes/data-ape/_data/config
   -  mkdir /var/lib/docker/volumes/data-ape/_data/logs
5. Prepare config files:
   - Copy da.properties from da-server into the new config dir
   - Create a NOCHECKIN_da.properties file populated with the commented out 'mongo.*' properties in the new config dir
6. Create a docker container:
   - docker run -d --name data-ape --restart=always -p 888:4100 --mount type=volume,source=data-ape,target=/docker-data/data-ape da-server:1.0.0-SNAPSHOT

This project is serving our own needs as light weight 'always on' db browser without much maintenance complexity.
Obvious missing features can be added if there is interest and related submissions are welcome.

Roadmap:
   - <s>Export result as CSV to Clipboard</s>
   - <s>Sort columns with drag & drop</s>
   - <s>Hide/View columns</s>
   - <s>Define default sort/hide/view in properties</s>
   - Create dockerfile for build
   - Light-weight JSON based record editing

Thanks for looking.
