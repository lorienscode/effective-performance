import requests
from bs4 import BeautifulSoup
import sqlite3  # For SQLite database (you can use other databases like MySQL, PostgreSQL, etc.)

# Fetch addresses from the database
def fetch_addresses_from_db(db_path):
    # Connect to the SQLite DB
    conn = sqlite3.connect(db_path)
    cursor = conn.cursor()
    
    # Fetch addresses from the database
    cursor.execute("SELECT address FROM addresses")  # 'addresses' = the name of the table ofnthe database we are pulling data from, replace with table name ofnyour database
    addresses = cursor.fetchall()
    
    # Terminate database connection
    conn.close()
    
    # Return a list of addresses
    return [address[0] for address in addresses]

# Scarper function with given address
def scrape_website_with_address(address):
    # URL of the website
    url = "https://example.com/search"
    
    # Search query parameter
    params = {
        'q': address,  # 'q' = parameter name of search bar, replace with actual parameter
    }
    
    # Browser headers
    headers = {
        'User-Agent': 'Mozilla/5.⁶0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/58.0.3029.110 Safari/537.3'
    }
    
    # GET request
    response = requests.get(url, params=params, headers=headers)
    
    # Check request status
    if response.status_code == 200:
        # Parse tcontent using BeautifulSoup
        soup = BeautifulSoup(response.content, 'html.parser')
        
        # Find the elements containing serlarch results

        results = soup.find_all('div', class_='result-class') # 'div' and 'class_' = the tag amd class used by the website, replace with actual class used by website
        
        # Extract for required information from results
        for result in results:
            title = result.find('h2').text  # 'h2' = tag used for the title, replace with actual tag
            link = result.find('a')['href']  # 'a' = tag used for the link, replace with actual tag used for the link
            print(f"Address: {address}")
            print(f"Title: {title}")
            print(f"Link: {link}")
            print("-" * 50)
    else:
        print(f"Failed to retrieve the webpage for address: {address}. Status code: {response.status_code}")

# Main function
def main():
    # Path to the SQLite database
    db_path = "addresses.db" # 'address.db' = path of database, replace with actual DB path
    
    # Fetch addresses from database
    addresses = fetch_addresses_from_db(db_path)
    
    # Iterate through the addresses and scrape the website
    for address in addresses:
        scrape_website_with_address(address)

if __name__ == "__main__":
    main()