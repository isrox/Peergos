<img src="https://teamcity.jetbrains.com/app/rest/builds/buildType:(id:OpenSourceProjects_Peergos_Build)/statusIcon"></a>

![Peergos Logo](https://peergos.org/theme/img/peergos/logo-main.svg)

Peergos
========

Peergos is building the next web - the private web, where end users are in control. Imagine web apps being secure by default and unable to track you. Imagine being able to control exactly what personal data each web app can see. Imagine never having to log in to an app ever again. You own your data and decide where it is stored and who can see it. At Peergos, we believe that privacy is a fundamental human right and we want to make it easy for everyone to interact online in ways that respect this right.

The foundation of Peergos is a peer-to-peer encrypted global filesystem with fine-grained access control designed to be resistant to surveillance of data content or friendship graphs. It will have a secure messenger, with optional interoperability with email, and a totally private and secure social network, where users are in control of who sees what (executed cryptographically). Our motto at Peergos is, "Control your data, control your destiny."

The name Peergos comes from the Greek word Πύργος (Pyrgos), which means stronghold or tower, but phonetically spelt with the nice connection to being peer-to-peer. Pronuniation: peer-goss (as in gossip).

Screenshots
----

See screenshots in the web-ui repository [https://github.com/Peergos/web-ui](https://github.com/Peergos/web-ui).

Demo
----
Want to try it out now? Here's our demo [https://demo.peergos.net](https://demo.peergos.net). 

Beta
----
Our beta is live at https://beta.peergos.net/. There are a limited number of free accounts available. 

Tech book
---------
You can read more detail about our features and architecture in our [tech book](https://book.peergos.org).

Media
---------
The slides of a talk introducing Peergos are [here](https://speakerdeck.com/ianopolous/peergos-architecture) 

Architecture talk at IPFS Lab Day:

[![Architecture Talk](https://img.youtube.com/vi/h54pShffxvI/0.jpg)](https://www.youtube.com/watch?v=h54pShffxvI)

Introduction and 2020 update:

[![Introduction and 2020 update](https://img.youtube.com/vi/oXMqYDLKWPc/0.jpg)](https://www.youtube.com/watch?v=oXMqYDLKWPc)

Introduction:

[![Introduction](https://img.youtube.com/vi/dCLboQDlzds/0.jpg)](https://www.youtube.com/watch?v=dCLboQDlzds)


Support
-------
If you would like to support Peergos development, then please make a 

[recurring donation less than 100 EUR per week](https://liberapay.com/peergos)

or a 

[larger or one off donation](https://donorbox.org/peergos). 

Audit
-----
Cure53 conducted an audit of Peergos in June 2019. The final report is [here](https://cure53.de/pentest-report_peergos.pdf).

Chat room
---------
There is a public chat room for Peergos on [Matrix](https://app.element.io/#/room/#peergos-chat:matrix.org).

Peergos aims
------------
 - To allow individuals to securely and privately store files in a peer to peer network which has no central node and is generally difficult to disrupt or surveil
 - To allow secure sharing of files with other users of the network without visible meta-data (who shares with who)
 - Allow web apps to be loaded and run durectly from Peergos in a sandbox that prevents data exfiltration and with user granted permissions
 - To have a beautiful user interface that any computer or mobile user can understand
 - To be independent of the central TLS Certificate Authority trust architecture
 - Self hostable - A user should be able to easily run Peergos on a machine in their home and get their own Peergos storage space, and social communication platform from it. 
 - A secure web interface

Project anti-aims
-----------------
 - Peergos does not provide anonymity, yet. Anonymity can be achieved by creating and only ever accessing a User account over Tor

Architecture
------------
1.0 Layers of architecture
 - 1: Peer-to-peer and data layer - [IPFS](https://ipfs.io) provides the data storage, routing and retrieval. A User must have at least one peergos instance storing their data for it to be available. 
 - 2: Authorization Layer - a key pair controls who is able to modify parts of the file system (every write is signed)
 - 3: Data storage - controlled by a given public key there is a [merkle-champ](https://en.wikipedia.org/wiki/Hash_array_mapped_trie) of encrypted chunks under random labels, without any cross links visible to the server (the server can't deduce the size of files)
 - 4: Encryption - Strong encryption is done on the user's machine using [TweetNaCl](http://tweetnacl.cr.yp.to/), with each 5MiB chunk of a file being encrypted independently. 
 - 5: Social layer implementing the concept of following or being friends with another user, without exposing the friend network to anyone.
 - 6: Sharing - Secure cryptographic sharing of files with friends.

2.0 Language
 - The IPFS layer is currently coded in Go - we have a minimal ipfs replacement - [ipfs-nucleau](https://github.com/peergos/ipfs-nucleus)
 - The server is coded to run on JVM to get portability and speed, predominantly Java
 - The web interface is mostly coded in Java and cross compiled to Javascript, with the exception of the Tweetnacl and scrypt libraries, and a small amount of GUI code in JS for Vue.js. 
 - Apps are written in HTML5

3.0 Nodes
 - There is a pki node which ensures unique usernames using a structure similar to certificate transparency. This data is mirrored on every peergos server. 
 - A new node contacts any public Peergos server to join the network

4.0 Trust
 - New versions of the software will be delivered through Peergos itself. (Able to be turned off by the user if desired)
 - A user who trusts a public Peergos server (and the SSL Certificate authority chain) can use the web interface over TLS
 - A less trusting user can run a Peergos server on their own machine and use the web interface over localhost
 - A more paranoid user can run a Peergos server on their own machine and use the CLI or the fuse binding
 - Servers are trustless - your data and metadata cannot be exposed even if your server is compromised (assuming your client is not compromised)
 - IPFS itself is not trusted and all data stored or retrieved from it is self-certifying. 
 - The data store (which may not be ipfs directly, but S3 compatible service for example) is also not trusted

4.0 Logging in
 - A user's username is used along with a random salt and the hash of their password and run through scrypt (with parameters 17, 8, 1, 96, though users can choose harder parameters if desired) to generate a symmetric key and a signing keypair. The signing keypiar is then used to auth and retrieve encrypted login data. This login data is then decrypted using the symmetric key to obtain the identity key pair, social keypair and root directory capability. This means that a user can log in from any machine without transfering any keys, and also that their keys are protected from a brute force attack (see slides mentioned above for a cost estimate).

5.0 Encryption
 - private keys never leave client node, two random symmetric keys are generated for every file or directory (explicitly not convergent encryption, which leaks information)

5.1 Post-quantum encryption
 - Files that haven't been shared with another user are already resistant to quantum computer based attacks. This is because the operations to decrypt them from logging in, to seeing plain-text, include only hashing and symmetric encryption, both of which are currently believed to not be significantly weakened with a quantum computer. 
 - Files that have been shared between users are, currently, vulnerable to a large enough quantum computer if an attacker is able to log the initial follow requests sent between the users (before the user retrieves and deletes them). This will be replaced with a post-quantum asymmetric algorithm as soon as a clear candidate arrives.  

6.0 Friend network
 - Anyone can send anyone else a "follow request". This amounts to "following" someone and is a one way protocol. This is stored in the target user's server, but the server cannot see who is sending the friend request (it is cryptographically blinded). 
 - The target user can respond to friend requests with their own friend request to make it bi-directional (the usual concept of a friend). 
 - Once onion routing is integrated, there will be no way for an attacker (or us) to deduce the friendship graph (who is friends with who). 
 
7.0 Sharing of a file (with another user, through a secret link, or publicly)
 - Once user A is being followed by user B, then A can share files with user B (B can revoke their following at any time)
 - File access control is based on [cryptree](https://raw.githubusercontent.com/ianopolous/Peergos/master/papers/wuala-cryptree.pdf) system used by Wuala
 - a link can be generated to a file or a folder which can be shared with anyone through any medium. A link is of the form https://demo.peergos.net/#KEY_MATERIAL which has the property that even the link doesn't leak the file contents to the network, as the key material after the # is not sent to the server, but interpreted locally in the browser. We have extended cryptree to protect much more metadata, including file size, names, thumbnails, directory structure and more. 
 - a user can publish a capability to a file or folder they control which makes it publicly visible

Usage - running locally to log in to another instance
-----
Use this method to login to a peergos account on another instance without any reliance on DNS or the TLS certificate authorities. 

1. Download a release from https://beta.peergos.net/public/peergos/releases

2. Install Java - You will need Java >= 11 installed (Java 17 is best). 

3. Run Peergos with:

```
java -jar Peergos.jar daemon
```
All the peergos data will be stored in ~/.peergos by default, which can be overridden with the environment var or arg - PEERGOS_PATH. 

You can then access the web interface and login via http://localhost:8000.

In this mode of operation all your writes are proxied directly to your home server. The local instance caches any blocks you access for faster subsequent access. 

Usage - self hosting
-----
Use this method to run a new home-server (which is best with a publicly routable IP, and always on machine) to create accounts on or migrate accounts to.

1. Download a release from https://beta.peergos.net/public/peergos/releases

2. Install Java - You will need Java >= 11 installed (Java 17 is best). 

3. Run Peergos with:
```
java -jar Peergos.jar daemon -generate-token true
```
All the peergos data will be stored in ~/.peergos by default, which can be overridden with the environment var or arg - PEERGOS_PATH

You can then access the web interface and signup via the localhost address printed, which includes a single use signup token.

The config is stored in $PEERGOS_PATH/config, so for subsequent runs you can just use the following unless you want to override any config
```
java -jar Peergos.jar daemon
```

Note that whichever Peergos server you sign up through (your home server) will be storing your data, so if you don't intend on leaving your Peergos server running permanently, then we recommend signing up on https://beta.peergos.net and then you can log in through a local Peergos instance and all your data will magically end up on the beta.peergos.net server. Peergos can work behind NATs and firewalls, but we recommend using a server with a public IP. If you want to expose your web interface publicly you will need to arrange a domain name and TLS certificates (we recommend using nginx and letsencrypt). 

If you don't set up a domain name and TLS you can still log in to your account from another Peergos instance, e.g. one you run locally on your laptop - connections are routed securely over P2P TLS1.3 streams to your home server. In this case, any writes are proxied to your home server so your data is always persisted there. 

### CLI
There are a range of commands available from a command line. You can run -help to find the available commands or details on any command. Most users should only need the *daemon* and *shell* commands, and maybe *fuse*. You can use the *migrate* command to move all your data to a new server (where the command is run). 

```
>> java -Djava.library.path=native-lib -jar Peergos.jar -help
Main: Run a Peergos command
Sub commands:
	daemon: The user facing Peergos server
	shell: An interactive command-line-interface to a Peergos server
	fuse: Mount a Peergos user's filesystem natively
	quota: Manage quota of users on this server
	server-msg: Send and receive messages to/from users of this server
	gateway: Serve websites directly from Peergos
	migrate: Move a Peergos account to this server
	identity: Create or verify an identity proof
	ipfs: Install, configure and start IPFS daemon
	pki: Start the Peergos PKI Server that has already been bootstrapped
	pki-init: Bootstrap and start the Peergos PKI Server
```

Development
--------
### Dependencies
Requires jdk11 and ant to build. Use the following to install dependencies:
#### On debian
```shell
sudo apt-get install ant
sudo apt-get install openjdk-11-jdk
```
#### On macOS
```shell
brew install ant # installs openjdk as a dependency
ant -version
Apache Ant(TM) version 1.10.8 compiled on May 10 2020
```
### Build
Note that this doesn't include any web ui, for the full build including the web interface build https://github.com/peergos/web-ui
```shell
ant dist
```
### Cross compile to JS
```shell
ant gwtc
```
### Run tests
You need to have ant-optional installed:
#### On debian
```shell
sudo apt-get install ant-optional
```
#### On macOS
Nothing additional is needed for the ant package on macOS.

Running tests will install and configure the correct version of IPFS automatically, run the daemon, and terminate it afterwards. 
```shell
ant test
```

### Development Notes
The ``ant compile`` target will only compile sources in src/peergos/{client,server,shared} folders.
